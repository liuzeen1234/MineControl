import unittest
import time
from unittest.mock import patch
from fishbone_miner import FishboneMiner, route, shell, Unsafe

class FishboneTests(unittest.TestCase):
    def miner(self):return FishboneMiner(25580,600,None,.15)

    def test_one_layer_shell_includes_floor_ceiling_and_excludes_passage(self):
        cells=shell((10,20,30))
        self.assertEqual(len(cells),34)
        self.assertNotIn((10,20,30),cells);self.assertNotIn((10,21,30),cells)
        self.assertIn((9,19,29),cells);self.assertIn((11,22,31),cells)
        self.assertNotIn((12,20,30),cells)
        self.assertEqual(len(shell((0,0,0),1)),26)

    def test_fishbone_route_returns_from_both_branches_before_main_advance(self):
        plan=list(route((0,0,0),'south',6,3,2))
        self.assertEqual(len(plan),22)
        previous=(0,0,0)
        for pos,_ in plan:
            self.assertEqual(sum(abs(a-b) for a,b in zip(pos,previous)),1);previous=pos
        self.assertEqual(plan[3],((1,0,3),'left'))
        self.assertEqual(plan[6],((0,0,3),'left_return'))
        self.assertEqual(plan[7],((-1,0,3),'right'))
        self.assertEqual(plan[10],((0,0,3),'right_return'))
        self.assertEqual(plan[11],((0,0,4),'main'))

    def test_query_requires_known_open_column_and_bounds_ignore_outside_results(self):
        m=self.miner()
        with self.assertRaisesRegex(Unsafe,'opened'):m.search_blocks((0,0,0))
        m.register_passage((0,0,0));calls=[]
        def response(_self,command,**args):
            calls.append((command,args))
            return {'blueprint':'1,0,0 minecraft:iron_ore\n2,0,0 minecraft:diamond_ore\n'}
        with patch('fishbone_miner.Miner.call',response):result=m.search_blocks((0,0,0))
        self.assertEqual(result,{(1,0,0):'minecraft:iron_ore'})
        self.assertEqual(calls,[('region.query',{'from':[-1,-1,-1],'to':[1,2,1]})])

    def test_refilled_cavity_is_not_a_valid_search_origin(self):
        m=self.miner();m.register_passage((0,0,0))
        with patch('fishbone_miner.Miner.call',return_value={'blueprint':'0,0,0 minecraft:gravel\n1,0,0 minecraft:iron_ore\n'}):
            self.assertEqual(m.search_blocks((0,0,0)),{})
        self.assertNotIn((0,0,0),m.opened)

    def test_observed_shell_air_exposes_corner_but_cannot_expand_query(self):
        m=self.miner();m.register_passage((0,0,0))
        with patch('fishbone_miner.Miner.call',return_value={'blueprint':'1,0,1 minecraft:iron_ore\n'}):
            m.search_blocks((0,0,0))
        self.assertIn((1,0,0),m.opened)
        with self.assertRaisesRegex(Unsafe,'opened'):m.search_blocks((1,0,0),height=1)

    def test_placed_floor_is_removed_from_open_cavities(self):
        m=self.miner();m.opened.add((0,-1,1))
        def fake_step(_self,destination):_self.events.append({'placed':(0,-1,1),'id':'minecraft:cobblestone'})
        with patch('fishbone_miner.StraightMiner.step',fake_step):m.step((0,0,1))
        self.assertNotIn((0,-1,1),m.opened)

    def test_unexpected_drop_stops_before_more_mining(self):
        m=self.miner();m.level=32
        with patch('fishbone_miner.StraightMiner.state',return_value={'player':{'y':31.4}}):
            with self.assertRaisesRegex(Unsafe,'Unexpected height'):m.state()

    def test_hidden_diagonal_candidates_do_not_turn_camera(self):
        m=self.miner();m.register_passage((0,0,0))
        m.call=lambda command,**args:{'x':.5,'y':0,'z':.5}
        m.look=lambda point:self.fail('Occluded corner must not direct aim')
        self.assertIsNone(m.visible_face((1,0,1)))

    def test_shared_face_must_be_first_hit_and_uses_surface_not_block_center(self):
        m=self.miner();m.register_passage((0,0,0));points=[]
        m.call=lambda command,**args:{'x':.5,'y':0,'z':.5}
        def look(point):points.append(point);return ((1,0,0),'minecraft:iron_ore')
        m.look=look
        result=m.visible_face((1,0,0));self.assertIsNotNone(result)
        self.assertAlmostEqual(points[0][0],1.001)
        m.look=lambda point:((0,1,0),'minecraft:stone')
        self.assertIsNone(m.visible_face((1,0,0)))

    def test_return_path_only_uses_previously_opened_passages(self):
        m=self.miner()
        for p in [(0,0,0),(1,0,0),(1,0,1)]:m.register_passage(p)
        self.assertEqual(m.known_path((1,0,1),(0,0,0)),[(1,0,0),(0,0,0)])
        self.assertIsNone(m.known_path((1,0,1),(0,0,1)))

    def test_ore_cavities_extend_search_then_return_to_planned_waypoint(self):
        m=self.miner();m.register_passage((0,0,0));m.deadline=time.monotonic()+600
        position=[(0,0,0)];searches=[];walks=[]
        m.state=lambda:None;m.foot=lambda:position[0]
        def search(center,height=2):
            searches.append((center,height))
            if center==(0,0,0):return {(1,0,0):'minecraft:iron_ore'}
            if center==(1,0,0) and height==1:return {(2,0,0):'minecraft:iron_ore'}
            return {}
        m.search_blocks=search
        m.visible_face=lambda q:(tuple(v+.5 for v in q),'minecraft:iron_ore')
        def clear(q,**kwargs):m.events.append({'mined':q,'id':'minecraft:iron_ore'})
        def step(q):walks.append(q);position[0]=q
        m.clear=clear;m.step=step
        m.local_ores((0,0,0))
        self.assertEqual([e['pos'] for e in m.ore_mined],[(1,0,0),(2,0,0)])
        self.assertEqual(searches[:3],[((0,0,0),2),((1,0,0),1),((2,0,0),1)])
        self.assertEqual(walks,[(1,0,0),(2,0,0),(1,0,0),(0,0,0)])
        self.assertEqual(position[0],(0,0,0))

    def test_ore_in_current_support_is_deferred_without_mining(self):
        m=self.miner();m.register_passage((0,0,0));m.deadline=time.monotonic()+600
        m.state=lambda:None;m.foot=lambda:(0,0,0)
        m.search_blocks=lambda *args,**kwargs:{(0,-1,0):'minecraft:iron_ore'}
        m.visible_face=lambda q:self.fail('Never target our occupied support')
        m.local_ores((0,0,0))
        self.assertEqual(m.ore_mined,[])
        self.assertIn((0,-1,0),m.deferred)

if __name__=='__main__':unittest.main()
