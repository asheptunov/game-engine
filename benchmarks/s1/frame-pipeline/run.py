from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
log = root / sys.argv[1]
log.parent.mkdir(parents=True, exist_ok=True)
with log.open('w', encoding='utf-8') as output:
    result = subprocess.run(sys.argv[2:], cwd=root, stdout=output, stderr=subprocess.STDOUT,
                            creationflags=subprocess.CREATE_NO_WINDOW)
print(log.read_text(encoding='utf-8', errors='replace')[-12000:])
sys.exit(result.returncode)
