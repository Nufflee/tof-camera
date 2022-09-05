# tof-camera

tof-camera is a simple Android app used for viewing (mostly) raw data from the LIDAR Time of Flight (ToF) camera on the device. Other than displaying raw data, this app supports dynamic ranging used for scaling the displayed colors.

# Possible improvements
- Using C++ through Android NDK for more energy and time efficient frame processing (currently it takes ~60 ms per frame)
- Implementing various other algorithms on the data such as edge detection, frame interpolation, fusion with visible light cameras, etc.
- Attempting to correct for/calibrate out warping artifacts, especially on the edges of the frame by characterizing the degree of wrapping at different distances

# Example video

https://user-images.githubusercontent.com/19150550/188508421-937b0ca5-3726-4618-b502-f00e8458c249.mp4
