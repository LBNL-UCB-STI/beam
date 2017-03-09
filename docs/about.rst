
About BEAM
==========

The BEAM Model stands for Behavior, Energy, Autonomy, and Mobility. The model will serve as a framework for a series of research studies in sustainable transportation at Lawrence Berkeley National Laboratory and the UC Berkeley Institute for Transportation Studies.  

As of Fall 2016, the first phase of model development is complete. The initial focus is on the simulation of plug-in electric vehicles (PEVs) in the context of light-duty, personal vehicle transport and the associated interactions with charging infrastructure and the electric grid. Future work with BEAM will include extending the model to a broader set of use cases including PEVs in the context of shared and autonomous fleets providing mobility as a service.

The key features of BEAM are summarized here and described in more in the draft report titled “Modeling Plug-in Electric Vehicle Trips, Charging Demand and Infrastructure”.

* **MATSim Integration** BEAM leverages the MATSim modeling framework1, an open source simulation tool with a vibrant community of global developers. MATSim is extensible (BEAM is one of those extensions) which allows modelers to utilize a large suite of tools and plug-ins to serve their research and analytical interests.

* **Spatially Explicit** BEAM explicitly represents a road network along with the spatial mobility patterns of a population of individuals. Charging infrastructure is expensive, walking is slow, and metropolitan regions are large and diverse. It therefore is critical to consider the spatial distribution of charging infrastructure when considering aggregated impacts of PEVs on the electric grid (which also has non-negligible spatial heterogeneity).

* **Endogenous Mobility** MATSim takes an agent-based approach to transportation modeling which focuses on the emergent outcomes of self-interested agents who seek to maximize their personal utility functions. By integrating with MATSim, BEAM puts the utility and disutility of charging PEVs on the same scales as the utility and disutility of mobility in a transportation system. The impact of a given portfolio of chargers can therefore be evaluated in terms of the impact on mobility itself. The converse is also true, where changes associated with mobility (e.g. introducing competing modes of transport) impact the utilization of charging infrastructure.

* **Detailed Representation of Charging Infrastructure** In BEAM, individual chargers are explicitly represented in the region of interest. Chargers are organized as sites that can have multiple charging points which can have multiple plugs of any plug type. The plug types are  defined by their technical characteristics (i.e. power capacity, price, etc.) and their compatibility with vehicles types (e.g. Telsa chargers vs. CHAdeMO vs. SAE). Physical access to chargers is also represented explicitly, i.e., charging points can only be accessed by a limited number of parking spaces. Chargers are modeled as queues, which can be served in an automated fashion (vehicle B begins charging as soon as vehicle A ends) or manually by sending notifications to agents that it is their turn to begin a charging session.

* **Robust Behavioral Modeling** The operational decisions made by PEV drivers are modeled using discrete choice models, which can be parameterized based on the outcomes of stated preference surveys or reveled preference analyses. For example, the decision of whether and where to charge is currently modeled in BEAM as a nested logit choice that considers a variety of factors including the location, capacity, and price of all chargers within a search radius in addition to the state of charge of the PEV and features of the agent’s future mobility needs for the day. The utility functions for the model are in part based on empirical work by Wen et al.2 who surveyed PEV drivers and analyzed the factors that influence their charging decisions.

* **Flexible Integration with Power System Modeling** Because BEAM is based on open source software and is under active development by experienced simulation modelers, the tool can be flexibly integrated with grid modeling software either through customized data exchanges or through API development that allows models to directly interface and influence each other. Work is currently underway to integrate BEAM with both the PLEXOS transmission system model as well as the Gridlab-D distribution system model.

Contact Information
^^^^^^^^^^^^^^^^^^^
Team PI:

Anand Gopal
argopal@lbl.gov

Primary Technical Contacts: 

Colin Sheppard
colin.sheppard@lbl.gov

Rashid Waraich
rwaraich@lbl.gov

References
^^^^^^^^^^

1.	Horni, A., Nagel, K. and Axhausen, K.W. (eds.) 2016 [The Multi-Agent Transport Simulation MATSim](http://www.matsim.org/the-book). London: Ubiquity Press. DOI: http://dx.doi.org/10.5334/baw. License: CC-BY 4.0.
2.	Wen, Y., MacKenzie, D. & Keith, D. Modeling the Charging Choices of Battery Electric Vehicle Drivers Using Stated Preference Data. TRB Proc. Pap. No 16-5618
