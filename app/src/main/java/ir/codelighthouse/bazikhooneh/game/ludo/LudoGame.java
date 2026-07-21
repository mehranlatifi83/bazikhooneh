package ir.codelighthouse.bazikhooneh.game.ludo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LudoGame {
    public static final int PLAYERS = 4, PIECES = 4, HOME = -1, FINISH = 57;
    private static final int[] START = {0, 13, 26, 39};
    private static final int[] SAFE = {0, 8, 13, 21, 26, 34, 39, 47};
    private final int[][] progress = new int[PLAYERS][PIECES];
    private final boolean[] active = new boolean[PLAYERS];
    private final boolean[] bot = new boolean[PLAYERS];
    private int currentPlayer, die, consecutiveSixes, winner = -1;
    private boolean awaitingRoll = true;

    public LudoGame(int humanPlayers, boolean threeBots) {
        for (int[] pieces : progress) Arrays.fill(pieces, HOME);
        if (threeBots) { active[0] = true; for (int i=1;i<PLAYERS;i++){active[i]=true;bot[i]=true;} }
        else for (int i=0;i<Math.max(2,Math.min(4,humanPlayers));i++) active[i]=true;
    }
    public static LudoGame snapshot(int[][] positions,boolean[] active,boolean[] bots,int current,int die,boolean awaiting,int winner){return snapshot(positions,active,bots,current,die,awaiting,winner,0);}
    public static LudoGame snapshot(int[][] positions,boolean[] active,boolean[] bots,int current,int die,boolean awaiting,int winner,int consecutiveSixes){LudoGame game=new LudoGame(4,false);for(int p=0;p<PLAYERS;p++){System.arraycopy(positions[p],0,game.progress[p],0,PIECES);game.active[p]=active[p];game.bot[p]=bots[p];}game.currentPlayer=current;game.die=die;game.awaitingRoll=awaiting;game.winner=winner;game.consecutiveSixes=consecutiveSixes;return game;}

    public int currentPlayer(){return currentPlayer;} public int die(){return die;}
    public boolean awaitingRoll(){return awaitingRoll;} public int winner(){return winner;} public int consecutiveSixes(){return consecutiveSixes;}
    public boolean isBot(int player){return bot[player];} public boolean isActive(int player){return active[player];}
    public int progress(int player,int piece){return progress[player][piece];}

    public List<Integer> roll(int value) {
        if (!awaitingRoll || winner >= 0 || value < 1 || value > 6) throw new IllegalStateException("invalid_roll");
        die=value;awaitingRoll=false;
        if(value==6&&++consecutiveSixes==3){consecutiveSixes=0;endTurn();return new ArrayList<>();}
        if(value!=6)consecutiveSixes=0;
        List<Integer> legal=legalPieces();if(legal.isEmpty()){boolean extra=value==6;if(extra){awaitingRoll=true;}else endTurn();}return legal;
    }

    public List<Integer> legalPieces(){List<Integer> result=new ArrayList<>();if(awaitingRoll)return result;for(int piece=0;piece<PIECES;piece++){int at=progress[currentPlayer][piece];if((at==HOME&&die==6)||(at>=0&&at<FINISH&&at+die<=FINISH))result.add(piece);}return result;}

    public Move move(int piece){if(!legalPieces().contains(piece))throw new IllegalStateException("illegal_move");int player=currentPlayer,from=progress[player][piece];progress[player][piece]=from==HOME?0:from+die;List<String> captured=new ArrayList<>();int global=globalPosition(player,progress[player][piece]);if(global>=0&&!isSafe(global)){for(int other=0;other<PLAYERS;other++)if(other!=player)for(int target=0;target<PIECES;target++)if(globalPosition(other,progress[other][target])==global){progress[other][target]=HOME;captured.add(other+":"+target);}}
        if(allFinished(player)){winner=player;awaitingRoll=false;}else if(die==6||!captured.isEmpty()){awaitingRoll=true;}else endTurn();return new Move(player,piece,from,progress[player][piece],captured,winner);
    }

    private boolean allFinished(int player){for(int value:progress[player])if(value!=FINISH)return false;return true;}
    private void endTurn(){do currentPlayer=(currentPlayer+1)%PLAYERS;while(!active[currentPlayer]);awaitingRoll=true;die=0;}
    public static int globalPosition(int player,int value){return value<0||value>=52?-1:(START[player]+value)%52;}
    public static boolean isSafe(int global){for(int value:SAFE)if(value==global)return true;return false;}

    public static final class Move {public final int player,piece,from,to,winner;public final List<String> captured;Move(int player,int piece,int from,int to,List<String> captured,int winner){this.player=player;this.piece=piece;this.from=from;this.to=to;this.captured=captured;this.winner=winner;}}
}
