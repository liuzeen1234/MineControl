import importlib.util
import json
from pathlib import Path
import socket
import threading
import unittest

spec = importlib.util.spec_from_file_location('minectl', Path(__file__).resolve().parents[1] / 'cli/minectl.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class CliTests(unittest.TestCase):
    def test_command_mapping(self):
        cases = [(['key', 'hold', 'W', '20'], {'command': 'key.hold', 'key': 'W', 'ticks': 20}),
                 (['ui', 'set', '3', 'off'], {'command': 'ui.set', 'widget': 3, 'value': False}),
                 (['ui', 'type', '2', '测试', '--mode', 'append'], {'command': 'ui.type', 'widget': 2, 'text': '测试', 'mode': 'append'}),
                 (['mouse', 'move', '30', '-10'], {'command': 'mouse.move', 'dx': 30.0, 'dy': -10.0})]
        for arguments, expected in cases:
            request = cli.make_request(cli.parser().parse_args(arguments))
            request.pop('id')
            self.assertEqual(request, expected)

    def mock_exchange(self, responder):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); listener.listen()
            errors = []
            def serve():
                try:
                    with listener.accept()[0] as connection:
                        with connection.makefile('rb') as stream:
                            request = json.loads(stream.readline())
                        for chunk in responder(request):
                            connection.sendall(chunk)
                except Exception as error:
                    errors.append(error)
            worker = threading.Thread(target=serve); worker.start()
            try:
                return cli.exchange({'id': 'test', 'command': 'ping'}, listener.getsockname()[1], 2)
            finally:
                worker.join(3)
                self.assertFalse(worker.is_alive())
                self.assertEqual(errors, [])

    def test_fragmented_utf8_response(self):
        def responder(request):
            data = (json.dumps({'id': request['id'], 'ok': True, 'result': '测试'}, ensure_ascii=False) + '\n').encode()
            return [bytes([value]) for value in data]
        self.assertEqual(self.mock_exchange(responder)['result'], '测试')

    def test_mismatched_id(self):
        with self.assertRaisesRegex(ValueError, 'mismatched'):
            self.mock_exchange(lambda request: [b'{"id":"other","ok":true}\n'])

    def test_partial_response(self):
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.mock_exchange(lambda request: [b'{"id":"test","ok":true}'])


if __name__ == '__main__':
    unittest.main()
