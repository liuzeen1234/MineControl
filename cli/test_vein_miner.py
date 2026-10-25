import unittest
from unittest.mock import patch
from vein_miner import Terrain, Miner, parse_blueprint, AIR, Unsafe, at_destination, DIG, FLOOR

class VeinTests(unittest.TestCase):
    def test_removed_connector_still_traces_incidental_vein(self):
        blocks={(0,0,0):'minecraft:coal_ore',(1,0,0):AIR,(2,0,0):'minecraft:coal_ore'}
        class Fake(Miner):
            def scan(self,low,high):
                subset={p:b for p,b in blocks.items() if all(low[i]<=p[i]<=high[i] for i in range(3))}
                self.terrain.update(low,high,subset);return subset
        miner=Fake(0,1,None,.15)
        miner.events=[{'mined':(1,0,0),'id':'minecraft:coal_ore'}]
        seed,(family,members)=miner.incidental()
        self.assertEqual(family,'coal');self.assertEqual(members,{(0,0,0),(1,0,0),(2,0,0)})
        miner.veins=[{'members':list(members)}];self.assertIsNone(miner.incidental())
    def test_natural_sandstone_is_mineable_solid_support(self):
        self.assertIn('minecraft:sandstone',DIG);self.assertIn('minecraft:sandstone',FLOOR)
        self.assertNotIn('minecraft:sand',FLOOR)
    def test_vegetation_is_passable_but_not_support(self):
        terrain=Terrain();terrain.update((-2,-2,-2),(2,4,2),{(0,-1,0):'minecraft:stone',(0,0,0):'minecraft:short_grass'})
        self.assertTrue(terrain.standable((0,0,0)))
        terrain.blocks[0,-1,0]='minecraft:short_grass';self.assertFalse(terrain.standable((0,0,0)))
    def test_step_checks_swept_upper_head_block(self):
        terrain=Terrain();terrain.update((-3,-3,-3),(3,4,3),{(0,-1,0):'minecraft:stone',(0,2,0):'minecraft:oak_log'})
        self.assertTrue(terrain.standable((0,0,0)))
        self.assertFalse(terrain.can_step((1,1,0),(0,0,0)))
    def test_exploration_descends_instead_of_walking_over_void(self):
        class Fake(Miner):
            def walk(self,p):self.walked=p
        miner=Fake(0,1,None,.15)
        floor={(x,-2,z):'minecraft:stone' for x in range(-3,4) for z in range(-3,4)}
        miner.terrain.update((-3,-3,-3),(3,4,3),floor)
        miner.explore_step((0,0,0))
        self.assertEqual(miner.walked,(0,-1,1))
    def test_descending_step_clears_swept_headroom(self):
        class Fake(Miner):
            def __init__(self):
                super().__init__(0,1,None,.15);self.started=0;self.cleared=[]
                self.terrain.blocks[0,-1,0]='minecraft:stone'
            def foot(self):return (1,1,0)
            def mine_if_needed(self,p):self.cleared.append(p)
            def state(self):return {}
            def call(self,command,**args):
                if command=='position':return {'x':.5,'y':0,'z':.5}
                if command=='status':return {'on_ground':True}
                return {}
        miner=Fake()
        with patch('vein_miner.time.monotonic',return_value=1):miner.walk((0,0,0))
        self.assertEqual(miner.cleared,[(0,2,0),(0,1,0),(0,0,0)])
    def test_landing_accepts_subblock_collision_margin(self):
        self.assertTrue(at_destination({'x':28.30000001,'y':74,'z':-26.5001},(28,74,-27)))
        self.assertFalse(at_destination({'x':28.0,'y':74,'z':-26.5},(28,74,-27)))
        self.assertFalse(at_destination({'x':28.5,'y':74.5,'z':-26.5},(28,74,-27)))
    def test_v3_coordinates_and_namespace(self):
        self.assertEqual(parse_blueprint('# MCBLUEPRINT v3\n-2,-30,4 minecraft:deepslate_iron_ore axis=y\n'),{(-2,-30,4):'minecraft:deepslate_iron_ore'})
    def test_diagonal_family_closure(self):
        blocks={(0,0,0):'minecraft:iron_ore',(1,1,1):'minecraft:deepslate_iron_ore',(2,2,2):'minecraft:raw_iron_block',(1,0,0):'minecraft:coal_ore',(6,0,0):'minecraft:iron_ore'}
        class Fake(Miner):
            def scan(self,low,high):
                subset={p:b for p,b in blocks.items() if all(low[i]<=p[i]<=high[i] for i in range(3))}
                self.terrain.update(low,high,subset);return subset
        miner=Fake(0,1,None,.15);miner.scan((-1,-1,-1),(1,1,1))
        family,members=miner.trace((0,0,0))
        self.assertEqual(family,'iron');self.assertEqual(members,{(0,0,0),(1,1,1),(2,2,2)})
    def test_unknown_and_liquid_are_not_safe(self):
        terrain=Terrain();self.assertFalse(terrain.standable((0,0,0)))
        blocks={(x,-1,z):'minecraft:stone' for x in range(-2,3) for z in range(-2,3)}
        terrain.update((-2,-2,-2),(2,4,2),blocks);self.assertTrue(terrain.standable((0,0,0)))
        terrain.blocks[1,0,0]='minecraft:gravel';self.assertTrue(terrain.standable((0,0,0)))
        terrain.blocks[0,2,0]='minecraft:gravel';self.assertFalse(terrain.standable((0,0,0)))
        terrain.blocks[0,2,0]=AIR
        terrain.blocks[1,0,0]='minecraft:water';self.assertFalse(terrain.standable((0,0,0)))
    def test_route_keeps_known_support_and_does_not_dig_own_floor(self):
        terrain=Terrain();blocks={(x,-1,z):'minecraft:stone' for x in range(-12,13) for z in range(-5,6)}
        terrain.update((-12,-2,-5),(12,6,5),blocks)
        route=terrain.route((0,0,0),(8,0,0));self.assertIsNotNone(route);self.assertGreater(len(route),0)
        route_to_floor=terrain.route((0,0,0),(0,-1,0));self.assertTrue(route_to_floor)
        self.assertNotEqual(route_to_floor[-1],(0,0,0))

    def test_block_break_keeps_attack_held_while_polling(self):
        target=(0,0,1)
        class Fake(Miner):
            def __init__(self):
                super().__init__(0,1,None,.15);self.started=0;self.calls=[];self.scans=0
                self.terrain.update((-2,-2,-2),(2,3,3),{target:'minecraft:dirt'})
            def state(self):return {}
            def foot(self):return (0,0,0)
            def aim(self,point):pass
            def scan(self,low,high):
                self.scans+=1
                if self.scans>=6:self.terrain.blocks[target]=AIR
                return {}
            def call(self,command,**args):
                self.calls.append((command,args))
                if command=='observe':return {'target':{'kind':'block','pos':dict(zip(('x','y','z'),target))}}
                return {}
        miner=Fake()
        with patch('vein_miner.time.sleep'),patch('vein_miner.time.monotonic',return_value=1):miner.mine(target)
        self.assertEqual([c for c,a in miner.calls if c.startswith('mouse.')],['mouse.hold','mouse.up'])
        self.assertEqual(miner.calls[1][1]['ticks'],200)
        self.assertEqual(miner.events[-1]['id'],'minecraft:dirt')

if __name__=='__main__':unittest.main()
