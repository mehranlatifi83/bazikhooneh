import asyncio

from asgiref.sync import async_to_sync
from channels.testing import WebsocketCommunicator
from channels.db import database_sync_to_async
from django.test import TransactionTestCase
from django.test import override_settings

from config.asgi import application
from accounts.models import Account
from games.ludo_engine import initial_state
from games.models import LudoRoom, LudoSeat, Player, Room


class RoomWebSocketTests(TransactionTestCase):
    reset_sequences = True

    def setUp(self):
        self.room = Room.create_unique()
        self.x_player, self.x_token = Player.create_with_token(self.room, "X")
        self.o_player, self.o_token = Player.create_with_token(self.room, "O")
        self.room.state = Room.State.ACTIVE
        self.room.save()

    def test_two_players_receive_authoritative_state_and_turn_errors(self):
        async_to_sync(self._run_game_flow)()

    def test_both_players_can_start_a_rematch(self):
        async_to_sync(self._run_rematch_flow)()

    def test_leaving_closes_room_and_revokes_player_token(self):
        async_to_sync(self._run_leave_flow)()

    @override_settings(ONLINE_RECONNECT_GRACE_SECONDS=0)
    def test_disconnect_closes_room_after_reconnect_grace(self):
        async_to_sync(self._run_disconnect_timeout)()

    def test_query_string_credentials_are_rejected(self):
        async_to_sync(self._query_string_credentials_are_rejected)()

    def test_application_heartbeat_receives_pong(self):
        async_to_sync(self._heartbeat_receives_pong)()

    @override_settings(ONLINE_RECONNECT_GRACE_SECONDS=0)
    def test_closing_one_of_multiple_connections_does_not_forfeit(self):
        async_to_sync(self._multiple_connection_disconnect)()

    async def _query_string_credentials_are_rejected(self):
        socket = WebsocketCommunicator(
            application,
            f"/ws/v1/rooms/{self.room.code}/?token={self.x_token}",
            headers=[(b"host", b"localhost")],
        )
        self.assertFalse((await socket.connect())[0])

    async def _heartbeat_receives_pong(self):
        socket = self._socket(self.x_token)
        self.assertTrue((await socket.connect())[0])
        await socket.receive_json_from()
        await socket.receive_json_from()
        await socket.send_json_to({"type": "ping"})
        pong = await socket.receive_json_from()
        self.assertEqual("pong", pong["type"])
        self.assertIn("server_time", pong)
        await socket.disconnect()

    async def _multiple_connection_disconnect(self):
        first = self._socket(self.x_token)
        second = self._socket(self.x_token)
        self.assertTrue((await first.connect())[0])
        await first.receive_json_from()
        await first.receive_json_from()
        self.assertTrue((await second.connect())[0])
        await second.receive_json_from()
        await first.disconnect()
        await second.send_json_to({"type": "ping"})
        self.assertEqual("pong", (await second.receive_json_from())["type"])
        await asyncio.sleep(0.05)
        state = await database_sync_to_async(
            lambda: Room.objects.get(pk=self.room.pk).state)()
        self.assertEqual(Room.State.ACTIVE, state)
        await second.disconnect()

    async def _run_game_flow(self):
        x_socket = self._socket(self.x_token)
        o_socket = self._socket(self.o_token)
        self.assertTrue((await x_socket.connect())[0])
        self.assertTrue((await o_socket.connect())[0])

        self.assertEqual("state", (await x_socket.receive_json_from())["type"])
        self.assertEqual("state", (await o_socket.receive_json_from())["type"])
        self.assertEqual("presence", (await x_socket.receive_json_from())["type"])
        self.assertEqual("presence", (await x_socket.receive_json_from())["type"])
        self.assertEqual("presence", (await o_socket.receive_json_from())["type"])

        await o_socket.send_json_to(
            {"type": "action", "action": {"kind": "place", "destination": 0}}
        )
        self.assertEqual("not_your_turn", (await o_socket.receive_json_from())["error"])

        await x_socket.send_json_to(
            {
                "type": "action",
                "action_id": "move-1",
                "action": {"kind": "place", "destination": 0},
            }
        )
        x_state = await x_socket.receive_json_from()
        o_state = await o_socket.receive_json_from()
        self.assertEqual("X........", x_state["game"]["board"])
        self.assertEqual(x_state, o_state)
        self.assertEqual("move-1", x_state["action_id"])
        self.assertEqual("O", x_state["game"]["current_player"])

        await x_socket.disconnect()
        await o_socket.disconnect()

    async def _run_rematch_flow(self):
        x_socket = self._socket(self.x_token)
        o_socket = self._socket(self.o_token)
        await x_socket.connect()
        await o_socket.connect()
        await x_socket.receive_json_from()
        await x_socket.receive_json_from()  # X presence
        await o_socket.receive_json_from()
        await o_socket.receive_json_from()  # O presence
        await x_socket.receive_json_from()  # O presence

        for socket, destination in (
            (x_socket, 0), (o_socket, 3), (x_socket, 1),
            (o_socket, 4), (x_socket, 2),
        ):
            await socket.send_json_to({
                "type": "action", "action": {"kind": "place", "destination": destination}
            })
            await x_socket.receive_json_from()
            await o_socket.receive_json_from()

        await x_socket.send_json_to({"type": "rematch"})
        x_vote = await x_socket.receive_json_from()
        await o_socket.receive_json_from()
        self.assertTrue(x_vote["game"]["rematch_x"])

        await o_socket.send_json_to({"type": "rematch"})
        restarted = await x_socket.receive_json_from()
        await o_socket.receive_json_from()
        self.assertEqual(".........", restarted["game"]["board"])
        self.assertEqual("active", restarted["game"]["room_state"])
        self.assertEqual("O", restarted["game"]["current_player"])
        self.assertFalse(restarted["game"]["rematch_x"])

        await x_socket.disconnect()
        await o_socket.disconnect()

    async def _run_leave_flow(self):
        x_socket = self._socket(self.x_token)
        o_socket = self._socket(self.o_token)
        await x_socket.connect()
        await o_socket.connect()
        await x_socket.receive_json_from()
        await x_socket.receive_json_from()
        await x_socket.receive_json_from()
        await o_socket.receive_json_from()
        await o_socket.receive_json_from()

        await x_socket.send_json_to({"type": "leave"})
        x_state = await x_socket.receive_json_from()
        o_state = await o_socket.receive_json_from()
        self.assertEqual("closed", x_state["game"]["room_state"])
        self.assertEqual("x_left", o_state["game"]["outcome_reason"])

        replacement = self._socket(self.x_token)
        self.assertFalse((await replacement.connect())[0])
        await x_socket.disconnect()
        await o_socket.disconnect()

    async def _run_disconnect_timeout(self):
        x_socket=self._socket(self.x_token);o_socket=self._socket(self.o_token)
        await x_socket.connect();await o_socket.connect()
        await x_socket.receive_json_from();await x_socket.receive_json_from();await x_socket.receive_json_from()
        await o_socket.receive_json_from();await o_socket.receive_json_from()
        await x_socket.disconnect()
        presence=await o_socket.receive_json_from();state=await o_socket.receive_json_from()
        self.assertEqual("presence",presence["type"])
        self.assertEqual("closed",state["game"]["room_state"])
        self.assertEqual("x_left",state["game"]["outcome_reason"])
        await o_socket.disconnect()

    def _socket(self, token):
        return WebsocketCommunicator(
            application,
            f"/ws/v1/rooms/{self.room.code}/",
            headers=[(b"host", b"localhost"), (b"origin", b"http://localhost"),
                     (b"authorization", f"Bearer {token}".encode())],
        )


class LudoRoomWebSocketTests(TransactionTestCase):
    def test_roll_is_broadcast_to_every_connected_player_without_reconnect(self):
        async_to_sync(self._run_live_broadcast)()

    async def _run_live_broadcast(self):
        owner = await database_sync_to_async(Account.objects.create_user)(
            username="ludo_ws_owner", password="secure-password-123")
        opponent = await database_sync_to_async(Account.objects.create_user)(
            username="ludo_ws_opponent", password="secure-password-123")
        room = await database_sync_to_async(LudoRoom.create_unique)(owner)
        first, first_token = await database_sync_to_async(LudoSeat.create_human)(
            room, 0, owner)
        second, second_token = await database_sync_to_async(LudoSeat.create_human)(
            room, 1, opponent)

        def start_room():
            LudoSeat.objects.create(room=room, color=2, is_bot=True)
            LudoSeat.objects.create(room=room, color=3, is_bot=True)
            room.state = "active"
            room.game_state = initial_state(
                [True] * 4, [False, False, True, True], False)
            room.version = 1
            room.save()

        await database_sync_to_async(start_room)()
        first_socket = self._socket(room.code, first_token)
        second_socket = self._socket(room.code, second_token)
        self.assertTrue((await first_socket.connect())[0])
        self.assertTrue((await second_socket.connect())[0])
        await first_socket.receive_json_from()
        await second_socket.receive_json_from()

        await first_socket.send_json_to({
            "type": "roll", "action_id": "ludo-roll-1",
        })
        first_state = await first_socket.receive_json_from(timeout=2)
        second_state = await second_socket.receive_json_from(timeout=2)
        self.assertEqual("state", first_state["type"])
        self.assertEqual(first_state["payload"]["version"],
                         second_state["payload"]["version"])
        self.assertGreater(first_state["payload"]["version"], 1)
        await first_socket.disconnect()
        await second_socket.disconnect()

    @staticmethod
    def _socket(code, token):
        return WebsocketCommunicator(
            application,
            f"/ws/v1/ludo/{code}/",
            headers=[(b"host", b"localhost"),
                     (b"authorization", f"Bearer {token}".encode())],
        )
