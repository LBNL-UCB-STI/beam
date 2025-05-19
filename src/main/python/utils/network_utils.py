from pyproj import Transformer
import pandas as pd

def load_network(network_file, source_epsg):
    """
    Load and transform network data

    Args:
        network_file: Path to network CSV file
        source_epsg: Source EPSG code for coordinate transformation

    Returns:
        DataFrame with network data including transformed coordinates
    """
    print(f"Loading network data from {network_file}")

    # Read network file
    network = pd.read_csv(network_file)

    # Create transformer for coordinate conversion
    transformer = Transformer.from_crs(source_epsg, "EPSG:4326", always_xy=True)

    # Extract coordinates as numpy arrays for efficient batch processing
    from_x = network['fromLocationX'].values
    from_y = network['fromLocationY'].values
    to_x = network['toLocationX'].values
    to_y = network['toLocationY'].values

    # Transform coordinates in a batch (much faster than row-by-row)
    from_lng_lat = transformer.transform(from_x, from_y)
    to_lng_lat = transformer.transform(to_x, to_y)

    # Update the dataframe with transformed coordinates
    network['fromLocationX'] = from_lng_lat[0]  # longitude
    network['fromLocationY'] = from_lng_lat[1]  # latitude
    network['toLocationX'] = to_lng_lat[0]  # longitude
    network['toLocationY'] = to_lng_lat[1]  # latitude

    # Return only needed columns
    return network[['linkId', 'linkLength',
                    'fromLocationX', 'fromLocationY',
                    'toLocationX', 'toLocationY']]