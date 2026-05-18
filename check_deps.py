import sys
print("检查依赖库...")

try:
    import torch
    print(f"✓ torch {torch.__version__}")
except ImportError:
    print("✗ torch 未安装")

try:
    import tensorflow as tf
    print(f"✓ tensorflow {tf.__version__}")
except ImportError:
    print("✗ tensorflow 未安装")

try:
    import ultralytics
    print(f"✓ ultralytics {ultralytics.__version__}")
except ImportError:
    print("✗ ultralytics 未安装")

print()
print("尝试加载模型...")
try:
    from ultralytics import YOLO
    model = YOLO('yolov8s.pt')
    print("✓ 模型加载成功")
except Exception as e:
    print(f"✗ 模型加载失败: {e}")