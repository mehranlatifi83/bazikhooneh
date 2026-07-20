from django.test import SimpleTestCase

from games.engine import GameError, GameState, Phase, Status


class GameStateTests(SimpleTestCase):
    def test_six_placements_start_movement_phase(self):
        game = self._place(0, 1, 2, 3, 7, 8)
        self.assertEqual(Phase.MOVEMENT, game.phase)
        self.assertEqual("X", game.current_player)

    def test_piece_can_move_to_any_empty_cell(self):
        game = self._place(0, 1, 2, 3, 7, 8)
        game = game.apply("X", {"kind": "move", "source": 0, "destination": 6})
        self.assertEqual(".", game.board[0])
        self.assertEqual("X", game.board[6])

    def test_winning_placement_finishes_game(self):
        game = self._place(0, 3, 1, 4, 2)
        self.assertEqual(Status.X_WON, game.status)

    def test_out_of_turn_action_is_rejected(self):
        with self.assertRaisesMessage(GameError, "not_your_turn"):
            GameState().apply("O", {"kind": "place", "destination": 0})

    def test_moving_other_players_piece_is_rejected(self):
        game = self._place(0, 1, 2, 3, 7, 8)
        with self.assertRaisesMessage(GameError, "source_not_owned"):
            game.apply("X", {"kind": "move", "source": 1, "destination": 6})

    def _place(self, *cells):
        game = GameState()
        for cell in cells:
            game = game.apply(game.current_player, {"kind": "place", "destination": cell})
        return game
