from ultralytics import YOLO
import shutil
import os

print("开始导出 YOLOv8s 为 TensorFlow Lite...")

model = YOLO('yolov8s.pt')
print("模型已加载，开始导出...")

result = model.export(format='tflite')
print(f"导出结果: {result}")

assets_dir = r"E:\project\飞控\APP\app\src\main\assets"
os.makedirs(assets_dir, exist_ok=True)

tflite_file = None
for f in os.listdir('.'):
    if f.endswith('.tflite'):
        tflite_file = f
        break

if tflite_file:
    dest = os.path.join(assets_dir, 'yolov8s.tflite')
    shutil.copy(tflite_file, dest)
    size = os.path.getsize(dest) / (1024*1024)
    print(f"成功! 文件已复制到: {dest}")
    print(f"文件大小: {size:.2f} MB")
else:
    print("警告: 未找到 .tflite 文件")
    print("当前目录文件:", os.listdir('.'))