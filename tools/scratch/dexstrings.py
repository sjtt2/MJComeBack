import sys, struct, os, re, json

def uleb128(b, i):
    result = 0; shift = 0
    while True:
        x = b[i]; i += 1
        result |= (x & 0x7f) << shift
        if not (x & 0x80): break
        shift += 7
    return result, i

def strings(path):
    data = open(path, 'rb').read()
    size, off = struct.unpack_from('<II', data, 0x38)
    out = []
    for i in range(size):
        so = struct.unpack_from('<I', data, off + 4*i)[0]
        n, p = uleb128(data, so)
        e = data.index(b'\x00', p)
        out.append(data[p:e].decode('utf-8', 'replace'))
    return out

if __name__ == '__main__':
    d = sys.argv[1]
    pat = re.compile(sys.argv[2], re.I)
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 10000
    cnt = 0
    for f in sorted(os.listdir(d)):
        if not f.endswith('.dex'): continue
        for s in strings(os.path.join(d, f)):
            if pat.search(s):
                print(f'{f}\t{s}')
                cnt += 1
                if cnt >= limit: sys.exit(0)
