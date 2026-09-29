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
DIRECT = {'PerformanceState.kt': '74406a1ce6f265f8d638cd0562b419aa817a6669d834f8ca645abc899e99c2f0', 'CompanionRuntime.kt': '52c62e08afbf3dd9149857a6e762a88d34a62c4af8604c115d7299e2e031c5ad', 'ChatterboxTokenizer.kt': 'ccf3058faa8dfc77f1de1b08f22d6cd70f65b799732c8b7af12f12352917503f', 'ChatterboxEngine.kt': '9e973771a2de8451440c05bb04a8b9f24aa8e4111dc1549a29208e58c2caa4cf', 'ExpressiveSpeechService.kt': '6861d1ff8aaa2724821ca4ac3e76a54668b67de48a818c7f71a466a3ba657444', 'VoiceAuditions.kt': '4be53a44d262422f3a4a63a4de05550b54ff0aa435d7d0b9363d24f11b030789', 'VoiceV3Models.kt': 'ce45e8812fe898e855d79934e3049dca3fa4ddc7d62ce3700977f71a18cb84ce', 'PlaybackEnvelope.kt': '266f8de6b141ada2f39e0dc0614209723606b814923ef435355854df6e634cde', 'PcmSpeechOutput.kt': 'f802924df19c258c1f4994a5b2ce2712c0292271b1d8ecd712e5855e66cc3ab1', 'OnlineVoice.kt': '84c9f9ad531a9aaa9f3469502ae6ffd3351532a136f2e7f5b09b4d9c4d565530', 'LayeredAvatar.kt': 'c7ce86db2c1e877ae604751aa87987d1e596b482c3abc6fab0ada5746b01a7a8', 'RigMotion.kt': '376569c08881c75c4d4ae1458ff6409ac0db9fd044ba98ec9d9d12ed82314ca4', 'RigTransitions.kt': '59f1274042882ca3a42bc51397fb8040da62032bfb7cdf2e401a6de83847d5ff', 'SpeechPace.kt': '3c52d616a2130490afea0a3834ac49a79e553e4d731097513144a23e660ad760', 'NaturalVoiceStyles.kt': 'e3ac1230e039903af8624443557e13d6e25283973558f2a59043dea47a471256', 'Policy.kt': 'fbd3e0466dfc5e5208daa770a5c0b47004b7e8e4ab87d28bb126744ade47121b'}
SNAPSHOT = "86a1a798c0001dea640058e03a6b0b189eb9d18174692aae375030690cc0aa6d"
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
    if len(expected)!=49 or digest(json.dumps(expected,sort_keys=True,separators=(",",":")).encode())!=SNAPSHOT:
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
