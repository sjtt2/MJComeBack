import re
for f in ["XposedServiceHelper.class","XposedProvider.class"]:
    d=open(f,"rb").read()
    s=sorted(set(re.findall(rb"[ -~]{4,}", d)))
    print("==",f)
    for x in s:
        t=x.decode()
        print("  ",t)
