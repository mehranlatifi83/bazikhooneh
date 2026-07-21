package ir.codelighthouse.bazikhooneh.game.ludo;
import org.junit.Test;import java.util.Collections;import static org.junit.Assert.*;
public final class LudoGameTest{
 @Test public void pieceLeavesHomeOnlyWithSix(){LudoGame game=new LudoGame(2,false);assertTrue(game.roll(3).isEmpty());assertEquals(1,game.currentPlayer());assertEquals(4,game.roll(6).size());game.move(0);assertEquals(0,game.progress(1,0));assertEquals(1,game.currentPlayer());}
 @Test public void sixAllowsAnotherRoll(){LudoGame game=new LudoGame(2,false);game.roll(6);game.move(0);assertTrue(game.awaitingRoll());assertEquals(0,game.currentPlayer());}
 @Test public void threeSixesForfeitTurnWhenOptionIsEnabled(){LudoGame game=new LudoGame(2,false,true);game.roll(6);game.move(0);game.roll(6);game.move(0);assertTrue(game.roll(6).isEmpty());assertEquals(1,game.currentPlayer());}
 @Test public void thirdSixIsAllowedByDefault(){LudoGame game=new LudoGame(2,false);game.roll(6);game.move(0);game.roll(6);game.move(0);assertFalse(game.roll(6).isEmpty());assertEquals(0,game.currentPlayer());}
 @Test public void safeSquaresAreRecognized(){assertTrue(LudoGame.isSafe(0));assertTrue(LudoGame.isSafe(47));assertFalse(LudoGame.isSafe(12));}
 @Test public void captureOpportunityIdentifiesPlayerAndPiece(){int[][] positions={{13,-1,-1,-1},{1,-1,-1,-1},{-1,-1,-1,-1},{-1,-1,-1,-1}};LudoGame game=LudoGame.snapshot(positions,new boolean[]{true,true,false,false},new boolean[4],0,0,true,-1);game.roll(1);assertEquals(Collections.singletonList("1:0"),game.captureTargets(0));}
}
