import re


class Point:
    def __init__(self, x, y):
        self.x = x
        self.y = y


class BoundBox:
    def __init__(self, topLeft, rightBottom):
        self.topLeft = topLeft
        self.rightBottom = rightBottom

    def from_str(value):
        values = re.findall("([\d.]+)", value)
        if len(values) != 4:
            msg = f"Invalid BoundBox input: [{value}]. It must have 4 float values"
            raise Exception(msg)
        a = Point(float(values[0]), float(values[1]))
        b = Point(float(values[2]), float(values[3]))
        return BoundBox(a, b)

    def to_string(self):
        return f"[({self.topLeft.x},{self.topLeft.y})({self.rightBottom.x},{self.rightBottom.y})]"
