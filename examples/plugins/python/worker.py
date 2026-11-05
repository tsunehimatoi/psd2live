from sdk import run


def evaluate(request, objects):
    factor = request['config']['factor']
    return [dict(op='mesh_opacity', id=mesh['id'], value=mesh['opacity'] * factor)
            for mesh in request['meshes']]


run(evaluate)
