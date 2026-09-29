#!/usr/bin/env python3
"""Materialize the tested 10018 checkpoint into canonical source before building.

A readable edit receipt records exact before/after hashes. No encoded executable
payload, network download, branch mutation, or secret handling occurs in this tool.
Unknown intervening source edits cause failure rather than an overwrite.
"""
import argparse, hashlib, json, pathlib, subprocess
BASE = "e212d17bb1904de55fe7b6cbea40443548cc73dc"
REUSE = "a449b57fe3996d12d2bd4ce5a1f211d792c76ad9"
PREFIX = "unified/src/main/java/com/rosalina/unified/"
DIRECT = {'PerformanceState.kt': '74406a1ce6f265f8d638cd0562b419aa817a6669d834f8ca645abc899e99c2f0', 'CompanionRuntime.kt': '52c62e08afbf3dd9149857a6e762a88d34a62c4af8604c115d7299e2e031c5ad', 'ChatterboxTokenizer.kt': 'ccf3058faa8dfc77f1de1b08f22d6cd70f65b799732c8b7af12f12352917503f', 'ChatterboxEngine.kt': '9e973771a2de8451440c05bb04a8b9f24aa8e4111dc1549a29208e58c2caa4cf', 'ExpressiveSpeechService.kt': '955c6cbbc2c8214d17b4ea8565626b3c4fc0de6450e04094142369ddb70be957', 'VoiceAuditions.kt': '4be53a44d262422f3a4a63a4de05550b54ff0aa435d7d0b9363d24f11b030789', 'VoiceV3Models.kt': 'ce45e8812fe898e855d79934e3049dca3fa4ddc7d62ce3700977f71a18cb84ce', 'PlaybackEnvelope.kt': '266f8de6b141ada2f39e0dc0614209723606b814923ef435355854df6e634cde', 'PcmSpeechOutput.kt': 'b21d0b463fc0c7cf4154f42f7498fb63dc9329a53cfa67118e1905a1e68dfe00', 'OnlineVoice.kt': '595cdba94b254f01f8daf10079a37c21b0510c9b873f809fbc9f42263095db3b', 'LayeredAvatar.kt': 'd66b771dfe357065b819b774525e8765ce3f82b9a1b28cfa5f28ace5d38a50a8'}
SNAPSHOT = "078bbc0c1687564256d0b4415f54cd22ff145aa93fa4c2e2d03e06b04a000eaa"
PARTS = ["part00.json", "part01.json", "part02.json", "part04.json", "part05.json", "part06.json", "part07.json", "part08.json"]
CORE = ["app", "lib", "studio", "image-engine", "motion", "motion-engine", "unified-native",
        PREFIX+"ListenService.kt", PREFIX+"SpeechService.kt", PREFIX+"Models.kt", PREFIX+"NativeWorker.kt",
        "unified/src/device", "unified/src/emulator"]
def digest(data): return hashlib.sha256(data).hexdigest()
def git(*args): return subprocess.check_output(["git", *args])
def original(commit, path): return git("show", commit+":"+path)
def protect():
    entries=git("ls-tree", "-rz", BASE, "--", *CORE).split(b"\0")
    count=0
    for entry in entries:
        if not entry: continue
        meta, path=entry.split(b"\t",1); mode, kind, sha=meta.split(); name=path.decode()
        if kind!=b"blob": continue
        current=pathlib.Path(name)
        if not current.is_file() or current.is_symlink(): raise RuntimeError("Protected source missing: "+name)
        if git("hash-object", "--", name).strip()!=sha: raise RuntimeError("Protected 10015 source changed: "+name)
        count+=1
    session=pathlib.Path(PREFIX+"Session.kt").read_text()
    if "res.available>=3_500_000_000L" not in session: raise RuntimeError("Protected Live RAM guard changed")
    return count
def main(check=False):
    # A canonical additive Studio inventory supersedes only the old materialization
    # recipe; its verifier still calls protect() and verifies all inherited files.
    studio=pathlib.Path("tools/studio/inventory.json")
    if studio.is_file():
        subprocess.run(["python3","tools/studio/verify_source.py"],check=True)
        return
    here=pathlib.Path(__file__).resolve().parent
    if sorted(p.name for p in here.glob("part*.json"))!=sorted(PARTS): raise RuntimeError("Source receipt parts differ")
    records=[]
    for name in PARTS: records.extend(json.loads((here/name).read_text()))
    expected={PREFIX+n:h for n,h in DIRECT.items()}
    seen=set(expected)
    for r in records:
        p=r["path"]; parts=pathlib.PurePosixPath(p).parts
        if p in seen or not parts or any(x in ("..", "") for x in parts) or p.startswith("/"):
            raise RuntimeError("Invalid or duplicate source path")
        if p!="COMPLETION_10018.md" and not p.startswith(("unified/", "tools/")): raise RuntimeError("Source path out of scope")
        seen.add(p);expected[p]=r["resultSha256"]
    if len(expected)!=41 or digest(json.dumps(expected,sort_keys=True,separators=(",",":")).encode())!=SNAPSHOT:
        raise RuntimeError("Source checkpoint inventory differs from reviewed snapshot")
    from revisions import apply_revisions
    apply_revisions(check)
    pending=[]
    for r in records:
        path=pathlib.Path(r["path"])
        if path.is_file() and digest(path.read_bytes())==r["resultSha256"]: continue
        if check: raise RuntimeError("Canonical source hash mismatch: "+str(path))
        if "content" in r: data=r["content"].encode()
        else:
            if r["baseCommit"]!=REUSE: raise RuntimeError("Unapproved reuse commit")
            source=original(REUSE,str(path))
            if digest(source)!=r["baseSha256"]: raise RuntimeError("Reuse source hash mismatch: "+str(path))
            lines=source.decode().splitlines(keepends=True); end=0
            for a,b,text in r["edits"]:
                if not end<=a<=b<=len(lines): raise RuntimeError("Overlapping source edit")
                end=b
            for a,b,text in reversed(r["edits"]): lines[a:b]=text.splitlines(keepends=True)
            data="".join(lines).encode()
        if digest(data)!=r["resultSha256"]: raise RuntimeError("Source reconstruction checksum mismatch: "+str(path))
        if path.exists():
            accepted=set()
            for ref in (BASE,REUSE):
                proc=subprocess.run(["git","show",ref+":"+str(path)],capture_output=True)
                if proc.returncode==0: accepted.add(digest(proc.stdout))
            if path.is_symlink() or digest(path.read_bytes()) not in accepted:
                raise RuntimeError("Intervening source edit preserved; reconcile before proceeding: "+str(path))
        pending.append((path,data))
    for n,h in DIRECT.items():
        p=pathlib.Path(PREFIX+n)
        if not p.is_file() or digest(p.read_bytes())!=h: raise RuntimeError("Direct source checksum mismatch: "+str(p))
    for p,data in pending: p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    protected=protect()
    for name,h in expected.items():
        if digest(pathlib.Path(name).read_bytes())!=h: raise RuntimeError("Final source verification failed: "+name)
    qa=pathlib.Path("qa");qa.mkdir(exist_ok=True)
    (qa/"source-paths.txt").write_text("\n".join(sorted(expected))+"\n")
    (qa/"source-materialization.json").write_text(json.dumps({"baseline":BASE,"reuse":REUSE,"sourceFiles":expected,"protectedFiles":protected,"checkpointSha256":SNAPSHOT,"materialized":len(pending),"phoneAccepted":False},indent=2))
    print("Verified",len(expected),"checkpoint files and",protected,"unchanged protected files")
if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("--check",action="store_true");a=p.parse_args();main(a.check)
