import os
import sys
import zlib


def read_corrupted_file(filename, CHUNKSIZE=1024):
    d = zlib.decompressobj(zlib.MAX_WBITS | 32)
    with open(filename, 'rb') as f:
        result_str = ''
        buffer = f.read(CHUNKSIZE)
        try:
            while buffer:
                result_str += d.decompress(buffer).decode('utf-8')
                buffer = f.read(CHUNKSIZE)
        except Exception as e:
            print('Error: %s -> %s' % (filename, e))
        return result_str


work_directory = '/home/ubuntu/git/beam/src/main/python/gemini'
filename = '0.events.7Advanced.csv.gz'
chunk_size = 1024

if len(sys.argv) >= 2:
    filename = str(sys.argv[1])

if len(sys.argv) >= 3:
    chunk_size = int(sys.argv[2])

full_filename = os.path.expanduser(work_directory + "/" + filename)

result = read_corrupted_file(full_filename, chunk_size)
text_file = open(filename + "-fix", "w")
n = text_file.write(result)
text_file.close()
