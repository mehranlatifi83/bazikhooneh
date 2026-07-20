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

    async def _run_game_flow(self):
        x_socket = self._socket(self.x_token)
        o_socket = self._socket(self.o_token)
        self.assertTrue((await x_socket.connect())[0])
        self.assertTrue((await o_socket.connect())[0])

        self.assertEqual("state", (await x_socket.receive_json_from())["type"])
        self.assertEqual("state", (await o_socket.receive_json_from())["type"])

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

    def _socket(self, token):
        return WebsocketCommunicator(
            application,
            f"/ws/v1/rooms/{self.room.code}/?token={token}",
            headers=[(b"host", b"localhost"), (b"origin", b"http://localhost")],
        )
