Drop INT8 .tflite weights here to replace the bundled stub. Expected filenames
(see com.lyne.adas.l1.config.AdasConfig):

  object_yolov8n_int8.tflite        (Tier A/B object detector)
  object_yolov8n_nano_int8.tflite   (Tier C object detector)
  lane_ufld_int8.tflite             (Tier A/B lane)
  lane_ufld_nano_int8.tflite        (Tier C lane)
  sign_mobilenetv3_int8.tflite      (traffic sign classifier)

Label files (already present): object_labels.txt, sign_labels.txt

Until real weights are present the app runs on the in-code stub detectors
(no detections in normal mode; enable INJECT in the HUD to exercise alerts).

Full contract, input shapes, quantization and conversion scripts: see /models/README.md
at the project root.
