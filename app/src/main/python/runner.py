"""Runs user Python code for the Code tab (Chaquopy). Output is streamed to a Kotlin callback;
stop() interrupts the running code at the next line via a trace function."""
import io
import os
import sys
import traceback

_stop = False


class _Out(io.TextIOBase):
    def __init__(self, cb, is_err):
        self.cb, self.is_err = cb, is_err

    def write(self, s):
        if s:
            self.cb.onOutput(s, self.is_err)
        return len(s)

    def flush(self):
        pass


def stop():
    global _stop
    _stop = True


def _trace(frame, event, arg):
    if _stop:
        raise KeyboardInterrupt("Stopped by user")
    return _trace


def run(code, filename, cwd, stdin_text, cb):
    """Executes code as __main__. Returns the exit code (0 = success)."""
    global _stop
    _stop = False
    saved = (sys.stdout, sys.stderr, sys.stdin, os.getcwd(), list(sys.path), dict(sys.modules))
    sys.stdout, sys.stderr = _Out(cb, False), _Out(cb, True)
    sys.stdin = io.StringIO(stdin_text or "")
    try:
        if cwd:
            os.chdir(cwd)
            sys.path.insert(0, cwd)
        sys.settrace(_trace)
        exec(compile(code, filename, "exec"), {"__name__": "__main__", "__file__": filename})
        return 0
    except SystemExit as e:
        return e.code if isinstance(e.code, int) else 0
    except KeyboardInterrupt:
        sys.stderr.write("\n[Stopped]\n")
        return 130
    except BaseException:
        traceback.print_exc()
        return 1
    finally:
        sys.settrace(None)
        sys.stdout, sys.stderr, sys.stdin = saved[0], saved[1], saved[2]
        try:
            os.chdir(saved[3])
        except OSError:
            pass
        sys.path[:] = saved[4]
        # Forget modules imported from the project so edits are picked up next run.
        for name in list(sys.modules):
            if name not in saved[5]:
                mod = sys.modules.get(name)
                f = getattr(mod, "__file__", "") or ""
                if cwd and f.startswith(cwd):
                    del sys.modules[name]
