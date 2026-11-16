import re
s=open(r"tools/ui3.xml",encoding="utf-8").read()
for m in re.finditer(r"<node[^>]*>", s):
    t=m.group(0)
    if "发送" in t or "EditText" in t:
        b=re.search(r'bounds="([^"]+)"', t)
        txt=re.search(r'text="([^"]*)"', t)
        cls=re.search(r'class="([^"]*)"', t)
        print((txt.group(1) if txt else ""), (cls.group(1) if cls else ""), (b.group(1) if b else ""))
