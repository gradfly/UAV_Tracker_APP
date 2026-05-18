import sys
import subprocess

print("Python version:", sys.version)
print("Python executable:", sys.executable)
print("Python path:", sys.path)
print()

result = subprocess.run([sys.executable, "-m", "pip", "list"], capture_output=True, text=True)
print("pip list output:")
print(result.stdout)
if result.stderr:
    print("pip list errors:")
    print(result.stderr)