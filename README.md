# tof-camera

tof-camera is a simple Android app used for viewing (mostly) raw data from the LIDAR Time of Flight (ToF) camera on the device. Other than displaying raw data, this app supports dynamic ranging used for scaling the displayed colors.

# Possible improvements
- Using C++ through Android NDK for more energy and time efficient frame processing (currently it takes ~60 ms per frame)
- Implementing various other algorithms on the data such as edge detection, frame interpolation, fusion with visible light cameras, etc.

