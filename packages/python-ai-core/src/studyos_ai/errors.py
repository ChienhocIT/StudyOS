class PipelineError(Exception):
    def __init__(self, code: str, detail: str, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.safe_detail = detail
        self.retryable = retryable
