#!/usr/bin/env python3
"""
HPMS File Geodatabase to Shapefile Converter
Reads CA_HPMS_TMC2017.gdb and converts all layers to shapefiles
"""

import geopandas as gpd
import fiona
import os

def convert_gdb_to_shapefiles(gdb_path, output_dir="shapefiles"):
    """
    Convert all layers in a File Geodatabase to shapefiles

    Args:
        gdb_path (str): Path to the .gdb file
        output_dir (str): Output directory for shapefiles
    """

    # Check if geodatabase exists
    if not os.path.exists(gdb_path):
        print(f"Error: {gdb_path} not found!")
        return False

    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    print(f"Output directory: {os.path.abspath(output_dir)}")

    try:
        # List all layers in the geodatabase
        layers = fiona.listlayers(gdb_path)
        print(f"\nFound {len(layers)} layers in {gdb_path}:")
        for i, layer in enumerate(layers, 1):
            print(f"  {i}. {layer}")

        if not layers:
            print("No layers found in the geodatabase!")
            return False

        print(f"\nStarting conversion...")
        print("-" * 50)

        successful_conversions = 0
        failed_conversions = 0

        # Convert each layer
        for layer in layers:
            try:
                print(f"Processing layer: {layer}")

                # Read the layer
                gdf = gpd.read_file(gdb_path, layer=layer)

                # Create output shapefile path
                output_path = os.path.join(output_dir, f"{layer}.shp")

                # Convert to shapefile
                gdf.to_file(output_path, driver='ESRI Shapefile')

                # Print layer info
                print(f"  ✓ Converted successfully")
                print(f"  ✓ Records: {len(gdf)}")
                print(f"  ✓ Columns: {len(gdf.columns)}")
                print(f"  ✓ Geometry type: {gdf.geometry.geom_type.iloc[0] if len(gdf) > 0 else 'Unknown'}")
                print(f"  ✓ CRS: {gdf.crs}")
                print(f"  ✓ Output: {output_path}")

                # Show column names
                if len(gdf.columns) > 0:
                    cols = [col for col in gdf.columns if col != 'geometry']
                    print(f"  ✓ Attributes: {', '.join(cols[:5])}{' ...' if len(cols) > 5 else ''}")

                successful_conversions += 1
                print()

            except Exception as e:
                print(f"  ✗ Error converting {layer}: {str(e)}")
                failed_conversions += 1
                print()
                continue

        # Summary
        print("-" * 50)
        print(f"Conversion Summary:")
        print(f"  ✓ Successful: {successful_conversions}")
        print(f"  ✗ Failed: {failed_conversions}")
        print(f"  📁 Output directory: {os.path.abspath(output_dir)}")

        return successful_conversions > 0

    except Exception as e:
        print(f"Error reading geodatabase: {str(e)}")
        return False


def inspect_layer(gdb_path, layer_name):
    """
    Inspect a specific layer in detail

    Args:
        gdb_path (str): Path to the .gdb file
        layer_name (str): Name of the layer to inspect
    """
    try:
        gdf = gpd.read_file(gdb_path, layer=layer_name)

        print(f"\nDetailed inspection of layer: {layer_name}")
        print("-" * 50)
        print(f"Shape: {gdf.shape}")
        print(f"CRS: {gdf.crs}")
        print(f"Bounds: {gdf.total_bounds}")
        print(f"Geometry types: {gdf.geometry.geom_type.value_counts().to_dict()}")

        print(f"\nColumns:")
        for col in gdf.columns:
            if col != 'geometry':
                dtype = gdf[col].dtype
                non_null = gdf[col].count()
                print(f"  {col}: {dtype} ({non_null}/{len(gdf)} non-null)")

        print(f"\nFirst 3 records:")
        print(gdf.head(3))

    except Exception as e:
        print(f"Error inspecting layer {layer_name}: {str(e)}")


def main():
    """Main function"""

    # Configuration
    gdb_path = os.path.expanduser("~/Workspace/Simulation/sfbay/validation/hpms/CA_HPMS_TMC2017.gdb")
    output_dir = "shapefiles"

    print("HPMS File Geodatabase to Shapefile Converter")
    print("=" * 50)

    # Check if required packages are installed
    try:
        import geopandas
        import fiona
        print("✓ Required packages found")
    except ImportError as e:
        print(f"✗ Missing required package: {e}")
        print("Install with: pip install geopandas fiona")
        return

    # Convert all layers
    success = convert_gdb_to_shapefiles(gdb_path, output_dir)

    if success:
        # Optional: Inspect first layer in detail
        try:
            layers = fiona.listlayers(gdb_path)
            if layers:
                print(f"\nDetailed inspection of first layer...")
                inspect_layer(gdb_path, layers[0])
        except:
            pass

    print("\nScript completed!")


if __name__ == "__main__":
    main()