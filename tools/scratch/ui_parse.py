import re
s=open(r"tools/ui.xml",encoding="utf-8").read()
for m in re.finditer(r"<node[^>]*>", s):
    t=m.group(0)
    if 'android.widget.EditText' in t:
        b=re.search(r'bounds="([^"]+)"', t)
        txt=re.search(r'text="([^"]*)"', t)
        print(txt.group(1) if txt else "", b.group(1) if b else "")
