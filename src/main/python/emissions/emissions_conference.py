import matplotlib.pyplot as plt
import numpy as np

# Data from the CSV file
pollutants = ['NOx', 'VOC', 'PM2.5', 'PM10', 'CO2']
transport_percentages = [41.3, 47.0, 4.9, 2.1, 37.0]  # Fraction of transportation to all
mhd_percentages = [20.9, 1.6, 26.0, 24.0, 24.8]  # Fraction of MHD among transportation

# Calculate the actual contribution of MHD to total emissions
mhd_total_percentages = [t * m / 100 for t, m in zip(transport_percentages, mhd_percentages)]

# Calculate non-MHD percentages within transportation sector
non_mhd_percentages = [t - m for t, m in zip(transport_percentages, mhd_total_percentages)]

# Create figure and axis
fig, ax = plt.subplots(figsize=(14, 6))

# Create stacked bar chart with updated colors
bar_width = 0.6
index = np.arange(len(pollutants))

non_mhd_color = '#3498db'  # Light blue
mhd_color = '#e67e22'  # Orange

p1 = ax.bar(index, non_mhd_percentages, bar_width, label='Non-MHD', color=non_mhd_color)
p2 = ax.bar(index, mhd_total_percentages, bar_width, bottom=non_mhd_percentages, label='MHD', color=mhd_color)

# Customize the chart with larger fonts
ax.set_ylabel('Percentage of Total Emissions', fontsize=24)
ax.set_xlabel('', fontsize=24)
ax.set_title('Transportation Sector Contribution to Air Pollution and GHG Emissions\n'
             'Medium- and Heavy-duty vehicles (MHD) vs non-MHD', fontsize=22, fontweight='bold')
ax.set_xticks(index)
ax.set_xticklabels(pollutants, fontsize=22)

# Increase font size of axis labels
ax.tick_params(axis='both', which='major', labelsize=22)

# Update legend with larger font
ax.legend(fontsize=20, loc='upper right')

# Add percentage labels on the bars with improved formatting
def add_percentages(rectangles):
    for rect in rectangles:
        height = rect.get_height()
        if height > 0:
            ax.annotate(f'{height:.1f}%',
                        xy=(rect.get_x() + rect.get_width() / 2, rect.get_y() + height / 2),
                        xytext=(0, 0),
                        textcoords="offset points",
                        ha='center', va='center',
                        fontsize=14, color='white', fontweight='bold')

add_percentages(p1)
add_percentages(p2)

plt.figtext(0.5, -0.1, "NOTE: PM values show primary emissions only, excluding secondary formation.\nData: U.S. EPA 2017 NEI & 2019 GGES**",
            ha="center", fontsize=18, wrap=True)

# Adjust layout and save
plt.tight_layout()
plt.savefig('transport_mhd_pollution_chart.png', dpi=300, bbox_inches='tight')
print("The chart has been saved as 'transport_mhd_pollution_chart.png'")

# Close the figure
plt.close(fig)