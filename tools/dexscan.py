import struct, pathlib, re, sys

class Dex:
 def __init__(self,path):
  self.path=str(path); self.b=pathlib.Path(path).read_bytes()
  self.strings=[]
  n,o=self.pair(56)
  for i in range(n):
   p=self.u32(o+4*i); _,p=self.uleb(p); e=self.b.index(0,p)
   self.strings.append(self.b[p:e].decode('utf-8','replace'))
  n,o=self.pair(64); self.types=[self.strings[self.u32(o+4*i)] for i in range(n)]
  n,o=self.pair(72); self.protos=[]
  for i in range(n):
   _,r,p=struct.unpack_from('<III',self.b,o+12*i)
   ps=[] if not p else [self.types[self.u16(p+4+2*j)] for j in range(self.u32(p))]
   self.protos.append('('+''.join(ps)+')'+self.types[r])
  n,o=self.pair(80); self.fields=[]
  for i in range(n):
   c,t,s=struct.unpack_from('<HHI',self.b,o+8*i); self.fields.append(self.types[c]+'->'+self.strings[s]+':'+self.types[t])
  n,o=self.pair(88); self.methods=[]
  for i in range(n):
   c,p,s=struct.unpack_from('<HHI',self.b,o+8*i); self.methods.append(self.types[c]+'->'+self.strings[s]+self.protos[p])
  n,o=self.pair(96); self.classes={}; self.codes={}
  for i in range(n):
   c,flags,sup,interfaces,src,ann,data,values=struct.unpack_from('<8I',self.b,o+32*i)
   name=self.types[c]; self.classes[name]={'flags':flags,'fields':[],'methods':[]}
   if not data: continue
   counts=[]; p=data
   for _ in range(4): v,p=self.uleb(p); counts.append(v)
   for count in counts[:2]:
    idx=0
    for _ in range(count):
     v,p=self.uleb(p); idx+=v; f,p=self.uleb(p); self.classes[name]['fields'].append((self.fields[idx],f))
   for count in counts[2:]:
    idx=0
    for _ in range(count):
     v,p=self.uleb(p); idx+=v; f,p=self.uleb(p); code,p=self.uleb(p)
     m=self.methods[idx]; self.classes[name]['methods'].append((m,f,code)); self.codes[m]=code
 def u16(self,p): return struct.unpack_from('<H',self.b,p)[0]
 def u32(self,p): return struct.unpack_from('<I',self.b,p)[0]
 def pair(self,p): return struct.unpack_from('<II',self.b,p)
 def uleb(self,p):
  v=0; shift=0
  while True:
   b=self.b[p]; p+=1; v|=(b&127)<<shift
   if not b&128: return v,p
   shift+=7
 def insns(self,code):
  if not code: return []
  n=self.u32(code+12); a=struct.unpack_from('<'+'H'*n,self.b,code+16); i=0; out=[]
  while i<n:
   w=a[i]; op=w&255; size=1; extra=''; name=OPS.get(op,hex(op))
   if op==0 and w:
    if w==0x100: size=4+2*a[i+1]
    elif w==0x200: size=2+4*a[i+1]
    elif w==0x300: size=4+(a[i+1]*(a[i+2]|a[i+3]<<16)+1)//2
   elif op in [2,5,8,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29] or 0x2d<=op<=0x3d or 0x44<=op<=0x6d or 0x90<=op<=0xaf or 0xd0<=op<=0xe2: size=2
   elif op in [3,6,9,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c] or 0x6e<=op<=0x72 or 0x74<=op<=0x78 or op in [0xfc,0xfd]: size=3
   elif op==0x18: size=5
   elif op in [0xfa,0xfb]: size=4
   if op==0x1a: extra=repr(self.strings[a[i+1]])
   elif op==0x1b: extra=repr(self.strings[a[i+1]|a[i+2]<<16])
   elif op in [0x1c,0x1f,0x20,0x22,0x23,0x24,0x25]: extra=self.types[a[i+1]]
   elif 0x52<=op<=0x6d: extra=self.fields[a[i+1]]
   elif 0x6e<=op<=0x72 or 0x74<=op<=0x78: extra=self.methods[a[i+1]]
   out.append((i,op,name,a[i:i+size],extra)); i+=size
  return out
 def dump(self,m):
  c=self.codes[m]; print('\n'+m+' code='+hex(c))
  if c: print('registers,ins,outs,tries=',struct.unpack_from('<4H',self.b,c))
  for i,op,name,words,extra in self.insns(c): print(f'{i:04x}: {name:24} '+ ' '.join(f'{w:04x}' for w in words)+' '+extra)

OPS={0:'nop',1:'move',2:'move/from16',3:'move/16',4:'move-wide',7:'move-object',8:'move-object/from16',10:'move-result',11:'move-result-wide',12:'move-result-object',13:'move-exception',14:'return-void',15:'return',16:'return-wide',17:'return-object',18:'const/4',19:'const/16',20:'const',21:'const/high16',22:'const-wide/16',23:'const-wide/32',24:'const-wide',26:'const-string',27:'const-string/jumbo',28:'const-class',31:'check-cast',32:'instance-of',33:'array-length',34:'new-instance',35:'new-array',36:'filled-new-array',39:'throw',40:'goto',41:'goto/16',42:'goto/32',43:'packed-switch',44:'sparse-switch'}
for start,names in [(0x32,['if-eq','if-ne','if-lt','if-ge','if-gt','if-le','if-eqz','if-nez','if-ltz','if-gez','if-gtz','if-lez']),(0x44,['aget','aget-wide','aget-object','aget-boolean','aget-byte','aget-char','aget-short','aput','aput-wide','aput-object','aput-boolean','aput-byte','aput-char','aput-short']),(0x52,['iget','iget-wide','iget-object','iget-boolean','iget-byte','iget-char','iget-short','iput','iput-wide','iput-object','iput-boolean','iput-byte','iput-char','iput-short','sget','sget-wide','sget-object','sget-boolean','sget-byte','sget-char','sget-short','sput','sput-wide','sput-object','sput-boolean','sput-byte','sput-char','sput-short']),(0x6e,['invoke-virtual','invoke-super','invoke-direct','invoke-static','invoke-interface']),(0x74,['invoke-virtual/range','invoke-super/range','invoke-direct/range','invoke-static/range','invoke-interface/range'])]:
 for i,n in enumerate(names): OPS[start+i]=n

def all_dex(): return [Dex(p) for p in sorted(pathlib.Path('analysis/dex').glob('*.dex'))]
if __name__=='__main__':
 pattern=sys.argv[1]
 for d in all_dex():
  for c,info in d.classes.items():
   if re.search(pattern,c):
    print('\nCLASS',c,d.path,info['flags'])
    for f,flags in info['fields']: print('FIELD',f,hex(flags))
    for m,flags,code in info['methods']:
     print('METHOD',m,hex(flags))
     if len(sys.argv)>2 and re.search(sys.argv[2],m): d.dump(m)
