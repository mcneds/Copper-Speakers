"""Render small inventory icons directly from the supplied cuboids and textures."""
import math
from PIL import Image, ImageDraw


def render_icon(model, texture, default_uv, size=64):
    faces = []
    for cube in model.get('elements', []):
        x, y, z = cube['from']; X, Y, Z = cube['to']
        corners = {
            'north': [(X, Y, z), (x, Y, z), (x, y, z), (X, y, z)],
            'east': [(X, Y, Z), (X, Y, z), (X, y, z), (X, y, Z)],
            'up': [(x, Y, z), (X, Y, z), (X, Y, Z), (x, Y, Z)]}
        for direction, points in corners.items():
            if direction not in cube.get('faces', {}): continue
            face = cube['faces'][direction]
            uv = face.get('uv', default_uv(direction, cube['from'], cube['to']))
            faces.append((sum(p[0] + p[1] - p[2] for p in points), direction, points, texture(face['texture']), uv, face.get('rotation', 0)))
    image = Image.new('RGBA', (size, size))
    if not faces: return image
    def project(point):
        x, y, z = point
        return (x + z) / math.sqrt(2), (x - z) / math.sqrt(6) - y * math.sqrt(2 / 3)
    projected = [project(p) for face in faces for p in face[2]]
    left, right = min(p[0] for p in projected), max(p[0] for p in projected)
    top, bottom = min(p[1] for p in projected), max(p[1] for p in projected)
    scale = (size - 6) / max(right - left, bottom - top, 1)
    offset = ((size - (right - left) * scale) / 2, (size - (bottom - top) * scale) / 2)
    def screen(point):
        x, y = project(point)
        return (x - left) * scale + offset[0], (y - top) * scale + offset[1]
    draw = ImageDraw.Draw(image)
    for _, direction, points, sprite, uv, rotation in sorted(faces, key=lambda face: face[0]):
        points = [screen(p) for p in points]
        steps = max(sprite.width, sprite.height)
        shade = {'up': 1, 'north': .85, 'east': .7}[direction]
        def interpolate(u, v):
            return tuple((1-v)*((1-u)*points[0][i]+u*points[1][i]) + v*((1-u)*points[3][i]+u*points[2][i]) for i in range(2))
        for row in range(steps):
            for column in range(steps):
                u, v = (column + .5) / steps, (row + .5) / steps
                for _ in range(rotation // 90): u, v = v, 1-u
                tx = int((uv[0] + u*(uv[2]-uv[0])) * sprite.width / 16) % sprite.width
                ty = int((uv[1] + v*(uv[3]-uv[1])) * sprite.height / 16) % sprite.height
                r, g, b, alpha = sprite.getpixel((tx, ty))
                if alpha == 0: continue
                quad = [interpolate(column/steps, row/steps), interpolate((column+1)/steps, row/steps),
                        interpolate((column+1)/steps, (row+1)/steps), interpolate(column/steps, (row+1)/steps)]
                draw.polygon(quad, fill=(int(r*shade), int(g*shade), int(b*shade), alpha))
    return image
