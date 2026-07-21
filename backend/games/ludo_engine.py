import random

HOME=-1;FINISH=57;START=(0,13,26,39);SAFE={0,8,13,21,26,34,39,47}

def initial_state(active=None,bots=None):
    return {"positions":[[HOME]*4 for _ in range(4)],"active":active or [True]*4,"bots":bots or [False]*4,"current_player":0,"die":0,"awaiting_roll":True,"consecutive_sixes":0,"winner":-1,"last_event":{}}

def global_position(player,progress): return -1 if progress<0 or progress>=52 else (START[player]+progress)%52
def legal_pieces(state):
    if state["awaiting_roll"]:return []
    die=state["die"];player=state["current_player"]
    return [i for i,at in enumerate(state["positions"][player]) if (at==HOME and die==6) or (0<=at<FINISH and at+die<=FINISH)]
def next_turn(state):
    player=state["current_player"]
    while True:
        player=(player+1)%4
        if state["active"][player]:break
    state.update(current_player=player,die=0,awaiting_roll=True)
def roll(state,value=None):
    if not state["awaiting_roll"] or state["winner"]>=0:raise ValueError("cannot_roll")
    value=value or random.SystemRandom().randint(1,6);state["die"]=value;state["awaiting_roll"]=False
    state["consecutive_sixes"]=state["consecutive_sixes"]+1 if value==6 else 0
    state["last_event"]={"kind":"roll","player":state["current_player"],"die":value}
    if state["consecutive_sixes"]>=3:state["consecutive_sixes"]=0;next_turn(state);return []
    legal=legal_pieces(state)
    if not legal:
        if value==6:state["awaiting_roll"]=True
        else:next_turn(state)
    return legal
def move(state,piece):
    if piece not in legal_pieces(state):raise ValueError("illegal_move")
    player=state["current_player"];before=state["positions"][player][piece];after=0 if before==HOME else before+state["die"];state["positions"][player][piece]=after;captured=[];global_at=global_position(player,after)
    if global_at>=0 and global_at not in SAFE:
        for other in range(4):
            if other==player:continue
            for target,value in enumerate(state["positions"][other]):
                if global_position(other,value)==global_at:state["positions"][other][target]=HOME;captured.append([other,target])
    if all(value==FINISH for value in state["positions"][player]):state["winner"]=player;state["awaiting_roll"]=False
    elif state["die"]==6 or captured:state["awaiting_roll"]=True
    else:next_turn(state)
    state["last_event"]={"kind":"move","player":player,"piece":piece,"from":before,"to":after,"captured":captured}
def bot_piece(state,legal):
    player=state["current_player"]
    for piece in legal:
        if state["positions"][player][piece]+state["die"]==FINISH:return piece
    for piece in legal:
        after=0 if state["positions"][player][piece]==HOME else state["positions"][player][piece]+state["die"];at=global_position(player,after)
        if at>=0 and at not in SAFE:
            for other in range(4):
                if other!=player and any(global_position(other,value)==at for value in state["positions"][other]):return piece
    return next((p for p in legal if state["positions"][player][p]==HOME),legal[0])
def run_bots(state,max_actions=100):
    actions=0
    while state["winner"]<0 and state["bots"][state["current_player"]] and actions<max_actions:
        if state["awaiting_roll"]:legal=roll(state)
        else:legal=legal_pieces(state)
        if legal:move(state,bot_piece(state,legal))
        actions+=1
