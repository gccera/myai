package com.gccera.myai.engine.llm

/** Jinja chat templates that override a model file's own template (see ModelSpec.chatTemplate). */
object ChatTemplates {
    /**
     * Qwen ChatML. Accepts message content as a plain string or as LiteRT-LM's list of
     * {type: text} parts, and maps LiteRT-LM's "model" role to "assistant".
     * Verified with the community Qwen2.5-Coder-7B LiteRT-LM conversion.
     */
    const val CHATML = """{%- for message in messages -%}
<|im_start|>{% if message['role'] == 'model' %}assistant{% else %}{{ message['role'] }}{% endif %}
{% if message['content'] is string %}{{ message['content'] }}{% else %}{% for c in message['content'] %}{% if c['type'] == 'text' %}{{ c['text'] }}{% endif %}{% endfor %}{% endif %}<|im_end|>
{% endfor -%}
{%- if add_generation_prompt %}<|im_start|>assistant
{% endif -%}"""

    fun byName(name: String?): String? = when (name) {
        null -> null
        "chatml" -> CHATML
        else -> null
    }
}
