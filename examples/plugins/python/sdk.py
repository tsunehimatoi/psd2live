"""PSD2Live algorithm protocol 1; stdout is exclusively the NDJSON response stream."""
import hashlib
import json
import os
import struct
import sys


def output_id(instance, local_name):
    return 'modifier:' + instance + ':' + local_name.encode('utf-8').hex()


def read_floats(reference):
    with open(os.path.join(os.environ['PSD2LIVE_PLUGIN_RESOURCES'], reference['sha256']), 'rb') as stream:
        data = stream.read()
    if hashlib.sha256(data).hexdigest() != reference['sha256']:
        raise ValueError('Invalid resource checksum')
    return struct.unpack('<' + 'f' * (len(data) // 4), data)


def run(evaluate):
    objects = {}
    for line in sys.stdin:
        request = json.loads(line)
        try:
            if request['protocol'] != 1:
                raise ValueError('Unsupported protocol')
            if request['reset']:
                objects.clear()
            for key in request['removed']:
                objects.pop(key, None)
            objects.update(request['changed'])
            response = dict(protocol=1, id=request['id'], commands=evaluate(request, objects))
        except Exception as error:
            response = dict(protocol=1, id=request['id'], error=str(error))
        print(json.dumps(response, separators=(',', ':')), flush=True)
