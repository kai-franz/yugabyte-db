def compute_total(items):
    return sum(item.price * item.qty for item in items)


def describe(items):
    return f"{len(items)} items, total {compute_total(items)}"
