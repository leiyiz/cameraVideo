import cv2
import numpy as np

filename = "frog.jpg"
fps = 30
data = '1111111111111111111100000000000011111111111111000000000000111111111111110000000000000000000000000'
print(len(data))
delta_alpha = 0.1

img = cv2.imread(filename)
height, width, layers = img.shape
size = (width, height)

fourcc = cv2.VideoWriter_fourcc(*'mpeg')
vid = cv2.VideoWriter('info.mp4', fourcc, fps, size)


def getImg(alpha):
    overlay = img.copy() * 0
    output = img.copy()
    cv2.addWeighted(overlay, alpha, output, 1 - alpha, 0, output)
    return output


vid.write(getImg(1))

for i in data:
    if i is '0':
        for j in range(3):
            vid.write(img)
            vid.write(getImg(delta_alpha))
    else:
        for j in range(2):
            vid.write(img)
            vid.write(img)
            vid.write(getImg(delta_alpha))

for i in range(20):
    vid.write(getImg(1))

vid.release()
