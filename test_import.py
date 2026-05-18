import sys
print("Python version:", sys.version)
try:
    import ultralytics
    print("ultralytics version:", ultralytics.__version__)
except ImportError as e:
    print("ultralytics not found:", e)
try:
    import torch
    print("torch version:", torch.__version__)
except ImportError as e:
    print("torch not found:", e)