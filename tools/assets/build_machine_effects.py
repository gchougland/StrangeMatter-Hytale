"""Surgically writes only machine-link emitters; reuses existing particle sprites untouched."""
from build_gadget_effects import emit, sp

def main():
    emit('SM_Stabilizer_Link', [
        sp('SM_Stabilizer_Link_Filament', 'fissure', '#d6ffff', size=.48, life=(.23,.28), opacity=.95),
        sp('SM_Stabilizer_Link_Glow', 'star', '#58dcff', size=.19, life=(.20,.27), opacity=.72)], radius=1,duration=.4)
    colors={'GRAVITY':'#9555dd','TEMPORAL_BLOOM':'#ffd66d','ENERGETIC_RIFT':'#5eefff',
            'WARP_GATE':'#de83f3','ECHOING_SHADOW':'#4c91b2','THOUGHTWELL':'#b1fff1'}
    for kind,color in colors.items():
        name='SM_Condenser_Link_'+kind
        emit(name,[sp(name+'_Mote','star',color,size=.20,stretch=1.3,life=(.25,.36),opacity=.9),
                   sp(name+'_Trail','spark',color,size=.13,stretch=2.2,life=(.24,.32),opacity=.65)],radius=1,duration=.5)
    emit('SM_Condenser_Receipt',[
        sp('SM_Condenser_Receipt_Ring','ring','#8befff',size=.8,end_size=.15,life=(.4,.6),opacity=.7),
        sp('SM_Condenser_Receipt_Core','star','#ebfaff',size=.2,end_size=.05,life=(.3,.5),opacity=.9)],radius=1,duration=.8)

if __name__=='__main__':main()
