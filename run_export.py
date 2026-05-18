from ultralytics import YOLO
import shutil
import os
import sys

print("YOLOv8s 导出 TFLite 开始")
print("Python:", sys.version)

try:
    model = YOLO('yolov8s.pt')
    print("模型加载成功")

    result = model.export(format='tflite')
    print("导出完成:", result)

    tflite_file = None
    for f in os.listdir('.'):
        if f.endswith('.tflite'):
            tflite_file = f
            break

    if tflite_file:
        assets_dir = r"E:\project\飞控\APP\app\src\main\assets"
        os.makedirs(assets_dir, exist_ok=True)
        dest = os.path.join(assets_dir, 'yolov8s.tflite')
        shutil.copy(tflite_file, dest)
        size = os.path.getsize(dest) / (1024*1024)
        print(f"成功! 文件: {dest}")
        print(f"大小: {size:.2f} MB")
    else:
        print("未找到 tflite 文件")

except Exception as e:
    print(f"错误: {e}")
    import traceback
    traceback.print_exc()