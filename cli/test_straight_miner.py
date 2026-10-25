import unittest
from unittest.mock import patch
from straight_miner import StraightMiner, Unsafe

class VisibleTests(unittest.TestCase):
    def miner(self):
        return StraightMiner(25580,600,None,.15)

    def test_region_query_is_forbidden_even_via_inherited_scan(self):
        with self.assertRaisesRegex(Unsafe,'forbidden'):
            self.miner().scan((0,0,0),(0,0,0))

    def test_occluder_is_not_dug_to_reach_requested_cell(self):
        miner=self.miner()
        miner.look=lambda point:((0,1,1),'minecraft:stone')
        miner.call=lambda command,**args:{'x':.5,'y':0,'z':.5} if command=='position' else self.fail('Unexpected mining input')
        with self.assertRaisesRegex(Unsafe,'Occluded'):
            miner.clear((0,1,2))

    def test_hidden_ore_does_not_change_commands(self):
        def survey(hidden):
            miner=self.miner();actions=[]
            # Hidden world data deliberately has no input path into the survey.
            miner.look=lambda point:((0,1,1),'minecraft:stone')
            miner.clear=lambda point:actions.append(point)
            miner.exposed_ores((0,0,0))
            return actions
        self.assertEqual(survey('minecraft:diamond_ore'),survey('minecraft:stone'))
        self.assertEqual(survey('minecraft:diamond_ore'),[])

    def test_newly_exposed_ore_is_rechecked_after_each_break(self):
        miner=self.miner();remaining=[(1,1,0),(2,1,0)];mined=[]
        miner.look=lambda point:(remaining[0],'minecraft:iron_ore') if remaining else ((3,1,0),'minecraft:stone')
        def clear(point):
            self.assertEqual(point,remaining.pop(0));mined.append(point)
        miner.clear=clear
        miner.exposed_ores((0,0,0))
        self.assertEqual(mined,[(1,1,0),(2,1,0)])

    def test_gap_requests_bridge_before_walking(self):
        miner=self.miner();miner.look=lambda point:None
        calls=[];miner.bridge=lambda point:calls.append(point)
        miner.prepare_floor((0,0,1))
        self.assertEqual(calls,[(0,0,1)])

    def test_gravel_is_removed_then_gap_is_bridged(self):
        miner=self.miner();hits=iter([((0,-1,1),'minecraft:gravel'),None]);actions=[]
        miner.look=lambda point:next(hits)
        miner.clear=lambda point,**args:actions.append(('clear',point,args))
        miner.bridge=lambda point:actions.append(('bridge',point))
        with patch('straight_miner.time.sleep'):
            miner.prepare_floor((0,0,1))
        self.assertEqual(actions,[('clear',(0,-1,1),{'allow_falling':True}),('bridge',(0,0,1))])

    def test_solid_floor_does_not_consume_blocks(self):
        miner=self.miner();miner.look=lambda point:((0,-1,1),'minecraft:stone')
        miner.bridge=lambda point:self.fail('Unnecessary bridge')
        miner.prepare_floor((0,0,1))

    def test_falling_gravel_refilling_tunnel_is_cleared_again(self):
        miner=self.miner();cleared=[];floors=[]
        miner.clear=lambda point,**args:cleared.append(point)
        hits=iter([((0,1,1),'minecraft:gravel'),None,None,None])
        miner.look=lambda point:next(hits)
        miner.prepare_floor=lambda point:floors.append(point)
        miner.state=lambda:None
        miner.call=lambda command,**args:{'x':.5,'y':0,'z':1.5} if command=='position' else {}
        with patch('straight_miner.time.sleep'):
            miner.step((0,0,1))
        self.assertEqual(cleared,[(0,1,1),(0,1,1),(0,0,1)])
        self.assertEqual(floors,[(0,0,1)])

    def test_refilled_gravel_floor_is_removed_before_bridge(self):
        miner=self.miner();cleared=[];bridged=[]
        hits=iter([((0,-1,1),'minecraft:gravel'),((0,-1,1),'minecraft:gravel'),None])
        miner.look=lambda point:next(hits)
        miner.clear=lambda point,**args:cleared.append(point)
        miner.bridge=lambda point:bridged.append(point)
        with patch('straight_miner.time.sleep'):
            miner.prepare_floor((0,0,1))
        self.assertEqual(cleared,[(0,-1,1)]*2)
        self.assertEqual(bridged,[(0,0,1)])

    def test_missing_material_stops_before_edge_movement(self):
        miner=self.miner();miner.look=lambda point:((0,-1,0),'minecraft:stone')
        def call(command,**args):
            if command=='position':return {'x':.5,'y':0,'z':.5}
            if command=='inventory':return {'slots':[]}
            self.fail('Unexpected input before materials check')
        miner.call=call
        with self.assertRaisesRegex(Unsafe,'hotbar'):
            miner.bridge((0,0,1))

    def test_rejected_placement_releases_inputs_and_restores_pickaxe(self):
        miner=self.miner();calls=[];positions=iter([
            {'x':.5,'y':0,'z':.5},{'x':.5,'y':0,'z':1.2}])
        observations=iter([
            {'target':{'kind':'block','pos':{'x':0,'y':-1,'z':0},'face':'south'}},
            {'target':None}])
        def call(command,**args):
            calls.append((command,args))
            if command=='position':return next(positions)
            if command=='inventory':return {'slots':[
                {'slot':0,'stack':{'id':'minecraft:netherite_pickaxe','count':1}},
                {'slot':2,'stack':{'id':'minecraft:cobblestone','count':8}}]}
            if command=='observe':return next(observations)
            return {}
        miner.call=call;miner.state=lambda:None;miner.aim=lambda point:None
        miner.look=lambda point:((0,-1,0),'minecraft:stone')
        with patch('straight_miner.time.sleep'):
            with self.assertRaisesRegex(Unsafe,'not confirmed'):
                miner.bridge((0,0,1))
        self.assertEqual(calls[-4:],[('mouse.up',{'button':'right'}),('key.up',{'key':'w'}),
                                    ('key.tap',{'key':'1'}),('key.up',{'key':'left_shift'})])

if __name__=='__main__':unittest.main()
