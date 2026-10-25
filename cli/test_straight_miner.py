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

    def test_unsupported_floor_stops_before_walking(self):
        miner=self.miner();miner.clear=lambda point:None;miner.look=lambda point:None
        with self.assertRaisesRegex(Unsafe,'landing floor'):
            miner.step((0,0,1))

if __name__=='__main__':unittest.main()
