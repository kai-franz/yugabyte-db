def compute_total(items):
    total = 0
    for item in items:
        total = total + item.price * item.qty
    return total


def describe(items):
    return f"{len(items)} items, total {compute_total(items)}"
