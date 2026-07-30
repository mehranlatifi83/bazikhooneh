package ir.codelighthouse.bazikhooneh.feature.tictactoe;

import android.os.Bundle;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;

public final class TicTacToeGuideActivity extends NavigableActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_tic_tac_toe_guide);
  }
}
