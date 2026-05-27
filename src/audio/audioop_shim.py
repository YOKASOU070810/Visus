"""Minimal audioop replacement for Python 3.13+ (PEP 594 removed audioop)."""
import array

def mul(fragment, width, factor):
    """Multiply all samples by factor."""
    if width == 2:
        arr = array.array('h', fragment)
        for i in range(len(arr)):
            arr[i] = max(-32768, min(32767, int(arr[i] * factor)))
        return arr.tobytes()
    elif width == 1:
        arr = array.array('b', fragment)
        for i in range(len(arr)):
            arr[i] = max(-128, min(127, int(arr[i] * factor)))
        return arr.tobytes()
    return fragment

def tomono(fragment, width, lfactor, rfactor):
    """Convert stereo to mono by mixing channels."""
    if width == 2:
        samples = array.array('h', fragment)
        mono = array.array('h')
        for i in range(0, len(samples), 2):
            if i + 1 < len(samples):
                v = int(samples[i] * lfactor + samples[i + 1] * rfactor)
                mono.append(max(-32768, min(32767, v)))
            else:
                mono.append(samples[i])
        return mono.tobytes()
    return fragment

def ratecv(fragment, width, nchannels, inrate, outrate, state):
    """Simple nearest-neighbor sample rate conversion.."""
    if inrate == outrate:
        return fragment, state
    if not fragment:
        return fragment, state

    ratio = inrate / outrate
    if width == 2:
        samples = array.array('h', fragment)
    elif width == 1:
        samples = array.array('b', fragment)
    else:
        return fragment, state

    n = len(samples)
    out_len = int(n / ratio)
    if out_len < 1:
        out_len = 1
    result = array.array(samples.typecode)
    for i in range(out_len):
        src_idx = int(i * ratio)
        if src_idx >= n:
            src_idx = n - 1
        result.append(samples[src_idx])

    return result.tobytes(), state
