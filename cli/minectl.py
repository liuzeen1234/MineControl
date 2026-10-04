#!/usr/bin/env python3
"""MineControl CLI. Python 3.9+, standard library only."""
import argparse
import json
import os
import socket
import sys
import uuid


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument('--port', type=int, default=int(os.environ.get('MINECONTROL_PORT', '25580')))
    root.add_argument('--timeout', type=float, default=20.0, help='socket timeout in seconds')
    sub = root.add_subparsers(dest='group', required=True)
    sub.add_parser('ping')
    sub.add_parser('status')
    ui = sub.add_parser('ui').add_subparsers(dest='action', required=True)
    ui.add_parser('list')
    click = ui.add_parser('click'); click.add_argument('widget', type=int)
    toggle = ui.add_parser('set'); toggle.add_argument('widget', type=int); toggle.add_argument('value', choices=['on', 'off'])
    text = ui.add_parser('type'); text.add_argument('widget', type=int); text.add_argument('text'); text.add_argument('--mode', choices=['replace', 'append'], default='replace')
    keys = sub.add_parser('key').add_subparsers(dest='action', required=True)
    for action in ['tap', 'down', 'up', 'hold']:
        item = keys.add_parser(action); item.add_argument('key')
        if action == 'hold': item.add_argument('ticks', type=int)
    mouse = sub.add_parser('mouse').add_subparsers(dest='action', required=True)
    for action in ['click', 'down', 'up', 'hold']:
        item = mouse.add_parser(action); item.add_argument('button', choices=['left', 'right'])
        if action == 'hold': item.add_argument('ticks', type=int)
    move = mouse.add_parser('move'); move.add_argument('dx', type=float); move.add_argument('dy', type=float)
    goto = mouse.add_parser('goto'); goto.add_argument('x', type=float); goto.add_argument('y', type=float)
    sub.add_parser('input').add_subparsers(dest='action', required=True).add_parser('reset')
    screen = sub.add_parser('wait').add_subparsers(dest='action', required=True).add_parser('screen')
    screen.add_argument('screen'); screen.add_argument('--timeout-ms', type=int, default=10000)
    return root


def make_request(args):
    values = vars(args).copy()
    group = values.pop('group')
    action = values.pop('action', None)
    for name in ['port', 'timeout']:
        values.pop(name)
    if group == 'ui' and action == 'set':
        values['value'] = values['value'] == 'on'
    return {'id': uuid.uuid4().hex, 'command': group + ('.' + action if action else ''), **values}


def exchange(request, port, timeout):
    if not 1 <= port <= 65535 or timeout <= 0:
        raise ValueError('port must be 1..65535 and timeout must be positive')
    with socket.create_connection(('127.0.0.1', port), timeout) as connection:
        connection.sendall((json.dumps(request, ensure_ascii=False) + '\n').encode('utf-8'))
        with connection.makefile('rb') as stream:
            line = stream.readline(1048577)
        if not line.endswith(b'\n') or len(line) > 1048576:
            raise ValueError('missing, incomplete or oversized response')
    response = json.loads(line)
    if not isinstance(response, dict) or response.get('id') != request['id'] or not isinstance(response.get('ok'), bool):
        raise ValueError('invalid response or mismatched request id')
    return response


def main(argv=None):
    args = parser().parse_args(argv)
    request = make_request(args)
    try:
        response = exchange(request, args.port, args.timeout)
    except (OSError, ValueError) as error:
        print(f'minectl: {error}. Check the client and port; after a timeout inspect status before retrying.', file=sys.stderr)
        return 2
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0 if response['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
