{{/* vim: set filetype=mustache: */}}
{{/* Expand the name of the chart. */}}

{{- define "beam-simulation.master.job" -}}
{{- $revision := htmlDate now -}}
{{- printf "master-job" -}}
{{- end -}}

{{- define "beam-simulation.master.job.container" -}}
{{- $revision := htmlDate now -}}
{{- printf "master-simulation-job" -}}
{{- end -}}

{{- define "beam-simulation.master.service" -}}
{{- $revision := htmlDate now -}}
{{- printf "master" -}}
{{- end -}}

{{- define "beam-simulation.worker.service" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker" -}}
{{- end -}}

{{- define "beam-simulation.worker.set" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-set" -}}
{{- end -}}

{{- define "beam-simulation.worker.job" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-job" -}}
{{- end -}}

{{- define "beam-simulation.worker.job.container" -}}
{{- $revision := htmlDate now -}}
{{- printf "worker-simulation-job" -}}
{{- end -}}
