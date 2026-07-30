package ir.codelighthouse.bazikhooneh.feature.ludo;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import ir.codelighthouse.bazikhooneh.navigation.AppNavigator;

public final class LudoMenuActivity extends NavigableActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_ludo_menu);
    findViewById(R.id.ludo_local).setOnClickListener(v -> showLocalOptions());
    findViewById(R.id.ludo_start_local).setOnClickListener(v -> startLocalGame());
    findViewById(R.id.ludo_bots).setOnClickListener(v -> startActivity(gameIntent("bots")));
    findViewById(R.id.ludo_online).setOnClickListener(v -> AppNavigator.openRooms(this, "ludo"));
    findViewById(R.id.ludo_guide)
        .setOnClickListener(v -> startActivity(new Intent(this, LudoGuideActivity.class)));
  }

  private void showLocalOptions() {
    findViewById(R.id.ludo_local_options).setVisibility(View.VISIBLE);
    findViewById(R.id.ludo_start_local).setVisibility(View.VISIBLE);
  }

  private void startLocalGame() {
    int selected = ((RadioGroup) findViewById(R.id.ludo_player_count)).getCheckedRadioButtonId();
    int players =
        selected == R.id.ludo_three_players ? 3 : selected == R.id.ludo_four_players ? 4 : 2;
    startActivity(gameIntent("local").putExtra(LudoGameActivity.EXTRA_PLAYERS, players));
  }

  private Intent gameIntent(String mode) {
    boolean thirdSixPenalty = ((CheckBox) findViewById(R.id.ludo_third_six_penalty)).isChecked();
    return new Intent(this, LudoGameActivity.class)
        .putExtra(LudoGameActivity.EXTRA_MODE, mode)
        .putExtra(LudoGameActivity.EXTRA_THIRD_SIX_PENALTY, thirdSixPenalty);
  }
}
