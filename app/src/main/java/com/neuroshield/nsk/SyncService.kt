package com.neuroshield.nsk

// Obsoleto. Este servicio nunca llegó a declararse en el AndroidManifest, por lo
// que jamás se ejecutó: la app quedaba sin servicio en primer plano y el sistema
// terminaba estrangulando la sincronización a los pocos días.
//
// Sustituido por SignalCollectorService, que sí está declarado y además recoge
// desbloqueos, luz ambiental y ventana de sueño.
