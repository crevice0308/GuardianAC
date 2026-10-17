"""No sockets opened: verify the real client's optional source bind fails closed."""
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, os.environ.get('GUARDIAN_CLIENT_SUPPORT', str(ROOT.parent / 'client')))
sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(Path(__file__).resolve().parent.parent/'runtime')))
from protocol_client import Client

class SourceBindingTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
    def test_loopback_source_is_passed_to_tcp_socket(self):
        sock = Mock()
        with patch('protocol_client.socket.create_connection', return_value=sock) as connect:
            client = Client(26731, 'GacRealMock', source_host='127.0.0.65')
            connect.assert_called_once_with(('127.0.0.1', 26731), timeout=10, source_address=('127.0.0.65', 0))
            self.assertEqual(sock.sendall.call_count, 2)
            client.close()
    def test_default_binding_unchanged(self):
        with patch('protocol_client.socket.create_connection', return_value=Mock()) as connect:
            Client(26732, 'GacRealMock').close()
            connect.assert_called_once_with(('127.0.0.1', 26732), timeout=10, source_address=None)
    def test_nonloopback_and_hostnames_never_open_a_socket(self):
        for source in ('8.8.8.8', '0.0.0.0', '192.168.1.2', '::1', 'localhost', 'example.com'):
            with self.subTest(source=source), patch('protocol_client.socket.create_connection') as connect:
                with self.assertRaises(ValueError):
                    Client(26731, 'GacRealMock', source_host=source)
                connect.assert_not_called()

if __name__ == '__main__': unittest.main()
