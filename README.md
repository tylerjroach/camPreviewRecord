camPreviewRecord
================

Built upon Grafika and HWEncoderExperiments

Here is my attempt to get a working video + audio recorder while showing the camera on a surface. Grafika built the video only portion, and HWEncoder experiments built video + audio but didn't display preview on surface. Merged both pieces of code toether to record camera preview using MediaCodec and MediaMuxer. Requires Android 4.3+. This is not a typical way to record video, but is necessary in certain circumstances (ex. saving and streaming at the same time).

This code is not ready for production. Ex:
You would not want to have a hardcoded camera preview size.
The code writes to one .mp4 file on external sd that is continually overwritten.

I hope this repo can be of help to someone! Let me know if you have any questions.

https://github.com/google/grafika
https://github.com/OnlyInAmerica/HWEncoderExperiments
