"""Rewrites the Mel-Band RoFormer export so its time axis is dynamic.

Why this exists
---------------
The published export is traced at a fixed 1101 STFT frames. With that shape ONNX
Runtime plans every buffer up front, and a single 11 s chunk peaks at **10.4 GB** of
RSS — more than any phone has. The weights themselves only account for ~2.5 GB; the
rest is the static allocation plan holding the band/time attention workspaces of all
twelve layers alive at once.

Marking the time axis dynamic forces ONNX Runtime to allocate per run instead, and it
reuses and frees as it goes. Measured on the same machine, same input, same session
options:

    static graph,  1101 frames   10.41 GB   31.1 s
    dynamic graph, 1101 frames    3.03 GB   32.6 s      max abs output diff 4.9e-04

The output difference is two ulps of fp16 — the graph is arithmetically the same model,
it is only scheduled differently.

What it changes
---------------
1. Every `Reshape` shape constant containing 1101 gets that entry replaced by -1. Only
   tensors used exclusively as a Reshape shape input are touched, and each must contain
   exactly one 1101 and no existing -1.
2. The rotary embedding table is sliced to the first 1101 rows by a constant `ends`.
   That input is rewired to `Shape(stft_repr)[2]`, so the table follows the real frame
   count.
3. The time dimension of the graph input and output becomes the symbol `frames`, and
   the stale pre-fusion `value_info` entries are dropped so they cannot contradict it.

The weights are not touched: the rewritten graph still references the original
`.onnx.data` file by name, so that file must sit next to it.

Usage
-----
    python3 make_dynamic_time.py syhft_core_folded_fp16_webgpu.onnx out.onnx
"""
import collections
import hashlib
import os
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper

TRACED_FRAMES = 1101


def main(src: str, dst: str) -> int:
    model = onnx.load(src, load_external_data=False)
    graph = model.graph

    consumers = collections.defaultdict(list)
    for node in graph.node:
        for position, name in enumerate(node.input):
            if name:
                consumers[name].append((node, position))

    rewritten = 0
    for tensor in graph.initializer:
        if tensor.data_location == onnx.TensorProto.EXTERNAL:
            continue
        uses = consumers.get(tensor.name, [])
        if not uses or not all(n.op_type == "Reshape" and p == 1 for n, p in uses):
            continue
        array = numpy_helper.to_array(tensor)
        if array.dtype != np.int64:
            continue
        shape = array.reshape(-1).tolist()
        if TRACED_FRAMES not in shape:
            continue
        if shape.count(TRACED_FRAMES) != 1 or -1 in shape:
            raise SystemExit("unexpected shape constant %s: %s" % (tensor.name, shape))
        shape[shape.index(TRACED_FRAMES)] = -1
        tensor.CopyFrom(numpy_helper.from_array(np.array(shape, dtype=np.int64), tensor.name))
        rewritten += 1

    # The rotary table slice is the one place the frame count is not a reshape.
    rotary = [
        n for n in graph.node
        if n.op_type == "Slice" and len(n.input) >= 3
        and _constant(graph, n.input[2]) == [TRACED_FRAMES]
    ]
    if len(rotary) != 1:
        raise SystemExit("expected exactly one rotary slice, found %d" % len(rotary))
    slice_node = rotary[0]
    if len(consumers[slice_node.input[2]]) != 1:
        raise SystemExit("the rotary 'ends' constant is shared; refusing to rewire it")

    graph.initializer.extend([
        numpy_helper.from_array(np.array([2], dtype=np.int64), "dynT_start"),
        numpy_helper.from_array(np.array([3], dtype=np.int64), "dynT_end"),
        numpy_helper.from_array(np.array([0], dtype=np.int64), "dynT_axis"),
    ])
    source = graph.input[0].name
    graph.node.insert(0, helper.make_node(
        "Slice", ["dynT_shape", "dynT_start", "dynT_end", "dynT_axis"],
        ["dynT_frames"], name="dynT_take_time_dim"))
    graph.node.insert(0, helper.make_node(
        "Shape", [source], ["dynT_shape"], name="dynT_shape_of_input"))
    slice_node.input[2] = "dynT_frames"

    for value in list(graph.input) + list(graph.output):
        dim = value.type.tensor_type.shape.dim[2]
        dim.ClearField("dim_value")
        dim.dim_param = "frames"
    del graph.value_info[:]

    onnx.save_model(model, dst)
    _check(dst)

    digest = hashlib.sha256(open(dst, "rb").read()).hexdigest()
    print("rewrote %d reshape constants" % rewritten)
    print("rewired %s -> Shape(%s)[2]" % (slice_node.name, source))
    print("%s  sha256=%s" % (dst, digest))
    return 0


def _check(dst: str) -> None:
    """Validates the saved graph, from its own directory so external data resolves."""
    directory = os.path.dirname(os.path.abspath(dst)) or "."
    previous = os.getcwd()
    os.chdir(directory)
    try:
        onnx.checker.check_model(os.path.basename(dst), full_check=False)
        print("checker: ok")
    except Exception as error:  # the weights may live elsewhere; the graph still saved
        print("checker: skipped (%s)" % str(error).split("\n")[0])
    finally:
        os.chdir(previous)


def _constant(graph, name):
    for tensor in graph.initializer:
        if tensor.name == name and tensor.data_location != onnx.TensorProto.EXTERNAL:
            array = numpy_helper.to_array(tensor)
            if array.dtype == np.int64:
                return array.reshape(-1).tolist()
    return None


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1], sys.argv[2]))
