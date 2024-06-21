from google_storage_analysis import StoragePrefix, get_storage_prefix, total_info

def print_beam_pilates(prefix: StoragePrefix):
    if is_beam(prefix):
        size, time = total_info(prefix.path)
        print(f'beam\t{prefix.path}\t{time}\t{size}')
    if is_pilates(prefix):
        size, time = total_info(prefix.path)
        print(f'pilates\t{prefix.path}\t{time}\t{size}')
    else:
        for p in prefix.prefixes:
            print_beam_pilates(get_storage_prefix(p))


def is_pilates(prefix: StoragePrefix) -> bool:
    if any(p.endswith('activitysim/') for p in prefix.prefixes):
        asim = get_storage_prefix(prefix.path + 'activitysim/')
        return any(p.split('/')[-2].startswith('year') for p in asim.prefixes)
    else:
        return False


def is_beam(prefix: StoragePrefix) -> bool:
    return any(p.endswith('ITERS/') for p in prefix.prefixes)


print_beam_pilates(get_storage_prefix(prefix=''))
