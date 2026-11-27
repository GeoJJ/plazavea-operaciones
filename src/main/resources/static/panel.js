document.addEventListener("click", event => {
  const boton = event.target.closest("[data-open]");
  if (!boton) return;
  const dialogo = document.getElementById(boton.dataset.open);
  if (dialogo instanceof HTMLDialogElement) dialogo.showModal();
});
