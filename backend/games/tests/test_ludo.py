from django.test import SimpleTestCase
from games.ludo_engine import initial_state, roll, move


class LudoEngineTests(SimpleTestCase):
    def test_six_releases_piece_and_grants_extra_roll(self):
        state = initial_state()
        self.assertEqual([0, 1, 2, 3], roll(state, 6))
        move(state, 0)
        self.assertEqual(0, state["positions"][0][0])
        self.assertTrue(state["awaiting_roll"])
        self.assertEqual(0, state["current_player"])

    def test_non_six_without_piece_advances_turn(self):
        state = initial_state()
        self.assertEqual([], roll(state, 3))
        self.assertEqual(1, state["current_player"])

    def test_three_sixes_forfeit_turn_when_enabled(self):
        state = initial_state(third_six_penalty=True)
        roll(state, 6)
        move(state, 0)
        roll(state, 6)
        move(state, 0)
        roll(state, 6)
        self.assertEqual(1, state["current_player"])

    def test_third_six_is_allowed_by_default(self):
        state = initial_state()
        roll(state, 6)
        move(state, 0)
        roll(state, 6)
        move(state, 0)
        self.assertTrue(roll(state, 6))
        self.assertEqual(0, state["current_player"])

    def test_capture_event_identifies_victim_player_and_piece(self):
        state = initial_state()
        state["positions"][0][0] = 13
        state["positions"][1][0] = 1
        roll(state, 1)
        move(state, 0)
        self.assertEqual([[1, 0]], state["last_event"]["captured"])
