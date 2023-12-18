#!/usr/bin/env python
# coding: utf-8

# In[1]:


from urllib.request import urlopen
import json
import pandas as pd

response = urlopen("https://api.github.com/repos/LBNL-UCB-STI/beam/pulls?state=closed&sort=created&direction=descr&per_page=100")
json_data = response.read().decode('utf-8', 'replace')

d = json.loads(json_data)
df_r = pd.json_normalize(d)
display(df_r.head(2))

df_r['real_id'] = df_r.apply(lambda r: r['html_url'].split('/')[-1], axis=1)
df = df_r[['real_id', 'html_url', 'closed_at', 'title']]
df


# In[ ]:




