import unittest
from unittest.mock import patch
from shaft_miner import ShaftMiner, Unsafe, main

class ShaftTests(unittest.TestCase):
    def miner(self):return ShaftMiner(25580,600,None,.15)

    def test_current_support_is_never_mined_and_landing_precedes_movement(self):
        m=self.miner();cleared=[];calls=[]
        hits=iter([((0,9,0),'minecraft:stone'),None,None,None,((0,8,1),'minecraft:stone')])
        m.look=lambda point:next(hits)
        m.clear=lambda point,**kwargs:cleared.append(point)
        positions=iter([{'x':.5,'y':10,'z':.5},{'x':.5,'y':9,'z':1.5}])
        def call(command,**args):
            calls.append(command)
            if command=='position':return next(positions)
            if command=='status':return {'on_ground':True}
            return {}
        m.call=call;m.state=lambda:None
        with patch('shaft_miner.time.sleep'):m.lower_step((0,9,1))
        self.assertEqual(cleared,[(0,11,1),(0,10,1),(0,9,1)])
        self.assertNotIn((0,9,0),cleared)
        self.assertEqual(m.events[-1]['descended_to'],(0,9,1))
        self.assertNotIn('key.hold',calls)

    def test_missing_landing_does_not_move_or_dig_deeper(self):
        m=self.miner();cleared=[];calls=[]
        hits=iter([((0,9,0),'minecraft:stone'),None,None,None,None])
        m.look=lambda point:next(hits)
        m.clear=lambda point,**kwargs:cleared.append(point)
        def call(command,**args):
            calls.append(command)
            if command=='position':return {'x':.5,'y':10,'z':.5}
            if command=='status':return {'on_ground':True}
            self.fail('Unexpected movement')
        m.call=call
        with patch('shaft_miner.time.sleep'):
            with self.assertRaisesRegex(Unsafe,'refuse blind drop'):m.lower_step((0,9,1))
        self.assertNotIn((0,8,1),cleared)
        self.assertNotIn('key.hold',calls)

    def test_nonadjacent_or_multilevel_drop_is_rejected(self):
        m=self.miner();m.call=lambda command,**kwargs:{'x':.5,'y':10,'z':.5}
        for dest in [(0,8,1),(0,9,0),(0,9,2)]:
            with self.assertRaisesRegex(Unsafe,'adjacent'):m.lower_step(dest)

    def test_no_terrain_query_or_server_command_is_available(self):
        m=self.miner()
        for command in ['region.query','command','server.command','chat','ui.type']:
            with self.assertRaisesRegex(Unsafe,'forbidden'):m.call(command)

    def test_failed_descent_never_starts_horizontal_mining(self):
        with patch('sys.argv',['shaft_miner.py','--target-y','32']), \
                patch('shaft_miner.ShaftMiner') as shaft, \
                patch('shaft_miner.StraightMiner') as horizontal:
            shaft.return_value.descend.return_value=False
            self.assertEqual(main(),1)
            horizontal.assert_not_called()

if __name__=='__main__':unittest.main()
