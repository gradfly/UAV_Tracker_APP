from ultralytics import YOLO
import os
import sys

print("Python version:", sys.version)
print("Current directory:", os.getcwd())
print()

try:
    print("正在加载模型...")
    model = YOLO('yolov8s.pt')
    print("模型加载成功！")

    print()
    print("正在导出为 TensorFlow Lite...")
    print("这可能需要几分钟，请耐心等待...")
    print()

    result = model.export(format='tflite')

    print()
    print("导出返回值:", result)

    print()
    print("查找 .tflite 文件...")
    for f in os.listdir('.'):
        print(f"  {f}")
        if f.endswith('.tflite'):
            print(f"找到 TFLite 文件: {f}")

except Exception as e:
    print(f"错误: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()

input("按回车键退出...")