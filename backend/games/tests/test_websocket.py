from asgiref.sync import async_to_sync
from channels.testing import WebsocketCommunicator
from django.test import TransactionTestCase

from config.asgi import application
from games.models import Player, Room


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

    def _socket(self, token):
        return WebsocketCommunicator(
            application,
            f"/ws/v1/rooms/{self.room.code}/?token={token}",
            headers=[(b"host", b"localhost"), (b"origin", b"http://localhost")],
        )
