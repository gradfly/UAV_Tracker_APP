from ultralytics import YOLO
import shutil
import os
import time

print("="*50)
print("YOLOv8s 导出为 TensorFlow Lite")
print("="*50)

print("\n[1/4] 加载 YOLOv8s 模型...")
model = YOLO('yolov8s.pt')
print("模型加载完成！")

print("\n[2/4] 导出为 TensorFlow Lite 格式...")
print("这可能需要几分钟，请耐心等待...")
export_result = model.export(format='tflite')
print(f"导出完成！结果: {export_result}")

print("\n[3/4] 查找导出的文件...")
tflite_files = []
for root, dirs, files in os.walk('.'):
    for f in files:
        if f.endswith('.tflite'):
            tflite_files.append(os.path.join(root, f))

if tflite_files:
    print(f"找到 {len(tflite_files)} 个 .tflite 文件:")
    for f in tflite_files:
        print(f"  - {f}")
else:
    print("未找到任何 .tflite 文件！")

print("\n[4/4] 复制到项目 assets 目录...")
assets_dir = r"E:\project\飞控\APP\app\src\main\assets"
os.makedirs(assets_dir, exist_ok=True)

if tflite_files:
    tflite_file = tflite_files[0]
    dest_path = os.path.join(assets_dir, 'yolov8s.tflite')
    shutil.copy(tflite_file, dest_path)
    print(f"已复制到: {dest_path}")

    file_size = os.path.getsize(dest_path) / (1024*1024)
    print(f"文件大小: {file_size:.2f} MB")
else:
    print("没有 .tflite 文件可复制！")

print("\n" + "="*50)
print("所有任务完成！")
print("="*50)