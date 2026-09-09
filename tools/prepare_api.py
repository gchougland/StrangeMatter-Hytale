"""Extract compile-only classes to avoid JDK ZipFS write attempts in restricted Windows sessions."""
import pathlib,zipfile,sys
root=pathlib.Path(__file__).resolve().parents[1]
archive=root/'build/deps/HytaleServer.jar'
target=root/'build/deps/server-api'
stamp=target/'.source-size'
fingerprint=f'{archive.stat().st_size}:{archive.stat().st_mtime_ns}'
if not stamp.exists() or stamp.read_text()!=fingerprint:
    target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if name.endswith('.class') and not name.startswith('META-INF/versions/'):
                path=target/name
                if not path.resolve().is_relative_to(target.resolve()):raise ValueError(name)
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(name))
    stamp.write_text(fingerprint)
    print('Prepared local Hytale API class directory for compilation.')
