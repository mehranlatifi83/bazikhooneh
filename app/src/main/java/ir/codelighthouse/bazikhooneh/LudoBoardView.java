package ir.codelighthouse.bazikhooneh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;
import java.util.List;
import ir.codelighthouse.bazikhooneh.game.ludo.LudoGame;

public final class LudoBoardView extends View {
    public interface Listener { void onPiece(int piece); }
    private static final int[][] TRACK={
        {6,0},{6,1},{6,2},{6,3},{6,4},{6,5},{5,6},{4,6},{3,6},{2,6},{1,6},{0,6},{0,7},
        {0,8},{1,8},{2,8},{3,8},{4,8},{5,8},{6,9},{6,10},{6,11},{6,12},{6,13},{6,14},{7,14},
        {8,14},{8,13},{8,12},{8,11},{8,10},{8,9},{9,8},{10,8},{11,8},{12,8},{13,8},{14,8},{14,7},
        {14,6},{13,6},{12,6},{11,6},{10,6},{9,6},{8,5},{8,4},{8,3},{8,2},{8,1},{8,0},{7,0}
    };
    private static final int[][][] FINAL={
        {{7,1},{7,2},{7,3},{7,4},{7,5},{7,6}},
        {{1,7},{2,7},{3,7},{4,7},{5,7},{6,7}},
        {{7,13},{7,12},{7,11},{7,10},{7,9},{7,8}},
        {{13,7},{12,7},{11,7},{10,7},{9,7},{8,7}}
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors={Color.rgb(211,47,47),Color.rgb(25,118,210),Color.rgb(46,125,50),Color.rgb(255,179,0)};
    private final float[][] legalCenters=new float[4][2];
    private LudoGame game; private Listener listener; private List<Integer> legal=Collections.emptyList();

    public LudoBoardView(Context context,AttributeSet attrs){super(context,attrs);setContentDescription(context.getString(R.string.ludo_board_description));setFocusable(false);}
    public void bind(LudoGame value,List<Integer> moves,Listener target){game=value;legal=moves;listener=target;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float size=Math.min(getWidth(),getHeight())*.96f,left=(getWidth()-size)/2f,top=(getHeight()-size)/2f,cell=size/15f;
        paint.setStyle(Paint.Style.FILL);paint.setColor(getResources().getColor(R.color.surface,null));canvas.drawRoundRect(left,top,left+size,top+size,24,24,paint);
        drawHome(canvas,left,top,cell,0,0,0);drawHome(canvas,left,top,cell,1,0,9);drawHome(canvas,left,top,cell,2,9,9);drawHome(canvas,left,top,cell,3,9,0);
        for(int i=0;i<TRACK.length;i++)drawCell(canvas,left,top,cell,TRACK[i][0],TRACK[i][1],LudoGame.isSafe(i)?Color.LTGRAY:Color.WHITE);
        for(int p=0;p<4;p++)for(int[] spot:FINAL[p])drawCell(canvas,left,top,cell,spot[0],spot[1],colors[p]);
        paint.setColor(Color.rgb(90,90,100));canvas.drawRect(left+6*cell,top+6*cell,left+9*cell,top+9*cell,paint);
        if(game==null)return;
        for(int p=0;p<4;p++)if(game.isActive(p))for(int piece=0;piece<4;piece++)drawPiece(canvas,left,top,cell,p,piece);
    }
    private void drawHome(Canvas c,float left,float top,float cell,int player,int gx,int gy){paint.setColor(colors[player]);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(left+gx*cell,top+gy*cell,left+(gx+6)*cell,top+(gy+6)*cell,cell*.35f,cell*.35f,paint);paint.setColor(Color.WHITE);c.drawRoundRect(left+(gx+1)*cell,top+(gy+1)*cell,left+(gx+5)*cell,top+(gy+5)*cell,cell*.25f,cell*.25f,paint);}
    private void drawCell(Canvas c,float left,float top,float cell,int x,int y,int color){float pad=1.5f;paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawRect(left+x*cell+pad,top+y*cell+pad,left+(x+1)*cell-pad,top+(y+1)*cell-pad,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(Color.DKGRAY);c.drawRect(left+x*cell+pad,top+y*cell+pad,left+(x+1)*cell-pad,top+(y+1)*cell-pad,paint);}
    private void drawPiece(Canvas c,float left,float top,float cell,int player,int piece){PointF point=piecePoint(left,top,cell,player,piece,game.progress(player,piece));float radius=cell*.31f;paint.setStyle(Paint.Style.FILL);paint.setColor(colors[player]);c.drawCircle(point.x,point.y,radius,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(Color.WHITE);c.drawCircle(point.x,point.y,radius,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(player==3?Color.BLACK:Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(cell*.42f);c.drawText(String.valueOf(piece+1),point.x,point.y+cell*.15f,paint);if(player==game.currentPlayer()&&legal.contains(piece)){legalCenters[piece][0]=point.x;legalCenters[piece][1]=point.y;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(6);paint.setColor(Color.BLACK);c.drawCircle(point.x,point.y,radius+5,paint);}}
    private PointF piecePoint(float left,float top,float cell,int player,int piece,int progress){int x,y;if(progress<0){int[][] origins={{2,2},{2,11},{11,11},{11,2}};x=origins[player][0]+piece%2*2;y=origins[player][1]+piece/2*2;}else if(progress<52){int[] spot=TRACK[LudoGame.globalPosition(player,progress)];x=spot[0];y=spot[1];}else if(progress<LudoGame.FINISH){int[] spot=FINAL[player][progress-52];x=spot[0];y=spot[1];}else{x=7;y=7;}float offset=(progress>=0&&progress<52)?stackOffset(player,piece,progress,cell):0;return new PointF(left+(x+.5f)*cell+offset,top+(y+.5f)*cell-offset);}
    private float stackOffset(int player,int piece,int progress,float cell){int before=0;for(int p=0;p<4;p++)for(int i=0;i<4;i++)if((p<player||(p==player&&i<piece))&&game.progress(p,i)>=0&&game.progress(p,i)<52&&LudoGame.globalPosition(p,game.progress(p,i))==LudoGame.globalPosition(player,progress))before++;return (before%3-1)*cell*.14f;}
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()!=MotionEvent.ACTION_UP||listener==null)return true;float best=Float.MAX_VALUE;int selected=-1;for(int piece:legal){float dx=event.getX()-legalCenters[piece][0],dy=event.getY()-legalCenters[piece][1],distance=dx*dx+dy*dy;if(distance<best){best=distance;selected=piece;}}if(selected>=0&&best<12000){listener.onPiece(selected);performClick();}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
